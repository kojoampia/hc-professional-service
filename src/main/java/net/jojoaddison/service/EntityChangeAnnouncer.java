package net.jojoaddison.service;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.broker.EntityChangeAction;
import net.jojoaddison.security.SecurityUtils;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NamedThreadLocal;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterDeleteEvent;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;
import org.springframework.stereotype.Component;

/**
 * Every document this service writes or removes, announced once on {@code professional.event}
 * (backlog.md item 141; hc-admin's item 110 for the estate-wide decision behind it).
 *
 * <p>hc-admin turns each frame into one row of an audit trail — a record that something changed and
 * who changed it, <b>not</b> a copy of what this service now holds. That distinction is the whole
 * design and it decides the payload: five identifiers and no values. See
 * {@link DomainEventPublisher#publishEntityChange}.
 *
 * <h2>Why a listener rather than calls from the write paths</h2>
 *
 * <p>This repository has already paid for the alternative, twice, and the bill is written down both
 * times. {@code entity.created} is published by hand from ten resources and is therefore absent from
 * every write path nobody remembered — {@code ProfileService.updatePushPreferences} creates a profile
 * and announces nothing, which {@code ProfileStatusAnnouncer}'s javadoc records. And backlog.md item
 * 49 is the bill for the same shape one level up: {@code ProfileStatus} hung off a table of four call
 * sites, the licence-renewal path was not on the table, and {@code isVerified} went true to false on
 * the server while hc-admin went on rendering "verified".
 *
 * <p><b>A list of call sites cannot fail when an eleventh one is written.</b> Spring Data raises
 * {@code AfterSaveEvent} for every persisted document however the save was reached, so a resource, a
 * service, a scheduler, a consumer or a migration written next month publishes without its author
 * knowing this class exists. {@code AbstractMongoEventListener<Object>} is the generic parameter that
 * makes it every collection rather than a list of types — which is exactly the scope item 110 asks
 * for, and is why hc-admin's {@code AuditLogCallback} is named as the reference implementation.
 *
 * <h2>The recursion guard, and why this one is empty</h2>
 *
 * <p>hc-admin's reference carries a guard whose comment reads: <i>"Without this the first write
 * recurses forever: saving an AuditLog fires onAfterSave, which saves another AuditLog."</i> It is
 * the first thing to look for in any implementation of this hook, and item 110 warns that publishing
 * makes the loop worse because it can now cross a network.
 *
 * <p><b>There is no such guard here because there is nothing yet to guard.</b> This announcer writes
 * no document at all — it reads the event, resolves an id and an actor, and hands a frame to a
 * publisher that talks only to Kafka. Nothing it does can raise another save event. That is a
 * property of the current implementation rather than a law, so it is stated rather than assumed:
 *
 * <p>⚠ <b>The moment anything on this path persists a document — an outbox row, a delivery receipt,
 * a failure record — this class must exclude that collection, or the first write will recurse until
 * the thread dies.</b> The same applies to a future consumer of {@code professional.event} that
 * writes back into this database: that loop has a broker in the middle, so it does not announce
 * itself as a stack overflow, it announces itself as traffic.
 *
 * <h2>One frame per write, deliberately unlike its neighbour</h2>
 *
 * <p>{@link ProfileStatusAnnouncer} batches to one frame per request, because the frame it sends is a
 * <em>snapshot</em> and an intermediate one can report a state no caller ever asked for. <b>This
 * announcer must not batch</b>, and the reason is the same one read backwards: these frames are a
 * <em>log</em>, and "the row changed twice" is the fact the audit trail exists to carry. Collapsing
 * two writes into one frame would lose it.
 *
 * <h2>What this deliberately does not cover</h2>
 *
 * <ul>
 *   <li><b>Query-based updates.</b> {@code MongoTemplate.updateFirst}/{@code updateMulti}/
 *       {@code findAndModify} raise no save event, by Spring Data's design — there is no entity to
 *       hand a listener. {@code ShiftTypeMigration} and {@code AngelDutyRoleMigration} write that
 *       way, so a bulk migration is silent on this channel. Announcing it would need a different
 *       mechanism, not a longer list of types.</li>
 *   <li><b>A delete that names no single {@code _id}</b> — {@code deleteAll}, a criteria delete. The
 *       event carries the query, not the documents it matched, so the count is unknown and the ids
 *       are gone. Skipped and logged rather than guessed at; see {@link #onAfterDelete}.</li>
 *   <li><b>Telling a first write with a caller-assigned id from a replacement.</b> See
 *       {@link EntityChangeAction}.</li>
 *   <li><b>Writes made outside this application</b> — mongosh, a restore, another service against the
 *       same database. There is no change stream here.</li>
 * </ul>
 */
@Component
public class EntityChangeAnnouncer extends AbstractMongoEventListener<Object> {

    private static final Logger log = LoggerFactory.getLogger(EntityChangeAnnouncer.class);

    /**
     * The entity instances this thread saw reach conversion with no id, and which will therefore be
     * inserts rather than replacements.
     *
     * <p><b>Identity, not equality.</b> Domain documents here carry generated {@code equals} on their
     * id, so two unsaved entities are equal to one another and a {@code HashSet} would collapse them
     * — and an entity whose id is assigned between the two events would stop being findable under the
     * key it was filed by. {@link IdentityHashMap} compares references, which is the only thing that
     * stays constant across the pair.
     *
     * <p><b>Bounded, because the pairing is not guaranteed.</b> {@code onBeforeConvert} fires and then
     * the write may throw, in which case no {@code onAfterSave} arrives to consume the entry and the
     * reference stays on a pooled thread. Each entry is one reference and the set is cleared whenever
     * it passes {@link #IN_FLIGHT_CAP}, so a persistently failing write path costs a bounded amount of
     * memory and one warning rather than a leak nobody can see. A stale entry that is later consumed
     * is not itself a defect: re-saving the same instance after a failed insert is still an insert.
     */
    private final ThreadLocal<Set<Object>> newInFlight = new NamedThreadLocal<>("Entity changes awaiting a save");

    /** How many unconsumed conversions one thread may accumulate before the set is dropped. */
    static final int IN_FLIGHT_CAP = 10_000;

    private final DomainEventPublisher publisher;

    public EntityChangeAnnouncer(DomainEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Notes that this entity is about to be inserted rather than replaced.
     *
     * <p>The id is the only signal available, and it is only available <em>here</em>: the converter
     * assigns one during {@code write()}, so by {@code onAfterSave} every entity has an id and an
     * insert is indistinguishable from a replacement. {@code MongoTemplate.save} is itself an upsert
     * and does not decide otherwise.
     */
    @Override
    public void onBeforeConvert(BeforeConvertEvent<Object> event) {
        try {
            Object entity = event.getSource();
            if (entity == null || idOf(entity) != null) {
                return;
            }
            Set<Object> inFlight = newInFlight.get();
            if (inFlight == null) {
                inFlight = Collections.newSetFromMap(new IdentityHashMap<>());
                newInFlight.set(inFlight);
            }
            if (inFlight.size() >= IN_FLIGHT_CAP) {
                // Saves are being converted and not completing. Dropping the set costs later frames a
                // CREATED they would have been entitled to; keeping it costs the heap without bound.
                log.warn("Dropping {} unconsumed conversions — writes are reaching the converter and not completing", inFlight.size());
                inFlight.clear();
            }
            inFlight.add(entity);
        } catch (RuntimeException e) {
            log.error("Could not note a pending insert — the write it precedes stands", e);
        }
    }

    /**
     * A document was written. Whether that was a creation is decided by whether this thread noted it
     * at conversion.
     */
    @Override
    public void onAfterSave(AfterSaveEvent<Object> event) {
        try {
            Object entity = event.getSource();
            if (entity == null) {
                return;
            }
            announce(entity.getClass(), idOf(entity), wasNew(entity) ? EntityChangeAction.CREATED : EntityChangeAction.UPDATED);
        } catch (RuntimeException e) {
            // Announcing must never break the write path: the row is persisted by the time this runs.
            log.error("Could not announce an entity change after a save — the write it followed stands", e);
        }
    }

    /**
     * A document was removed.
     *
     * <p>Reported from the <em>query</em> rather than from the document, because after the fact there
     * is nothing else left to report — the same limitation hc-admin's reference records. A query
     * naming a single {@code _id} is one frame; anything broader is skipped, because the event says
     * how many rows matched nowhere and inventing a frame per possible match would put ids on the wire
     * that may never have existed.
     */
    @Override
    public void onAfterDelete(AfterDeleteEvent<Object> event) {
        try {
            String id = deletedIdIn(event.getDocument()).orElse(null);
            if (id == null) {
                // Deliberately debug: a criteria delete is a legitimate operation, and an INFO per
                // deleteAll during a test run would be noise rather than signal.
                log.debug("A delete on {} named no single id — not announced", event.getType().getSimpleName());
                return;
            }
            announce(event.getType(), id, EntityChangeAction.DELETED);
        } catch (RuntimeException e) {
            log.error("Could not announce an entity change after a delete — the delete stands", e);
        }
    }

    /**
     * Resolves the two values that do not survive the publisher's thread hop, then hands the frame
     * over.
     *
     * <p><b>The actor is read here, on the writing thread, and it is the account id.</b>
     * {@code SecurityUtils.getCurrentAccountId()} reads the {@code uid} claim and filters on the
     * minting issuer; {@code getCurrentUserLogin()} is the wrong value and this subsystem has already
     * put it on a wire under the name {@code accountId} once — see
     * {@link DomainEventPublisher#publishEntityChange} for why that was tolerable there and is not
     * here. Empty means no account was behind this write, which is the ordinary case for a scheduler,
     * a startup runner, a Kafka consumer or a migration, and the frame says so by carrying no actor
     * rather than by carrying a placeholder.
     */
    private void announce(Class<?> type, String entityId, EntityChangeAction action) {
        if (entityId == null) {
            log.debug("A {} on {} resolved to no id — not announced", action, type.getSimpleName());
            return;
        }
        publisher.publishEntityChange(
            type.getSimpleName(),
            entityId,
            action,
            SecurityUtils.getCurrentAccountId().orElse(null),
            Instant.now()
        );
    }

    /** Consumes this thread's note, if it left one. Present means the save was an insert. */
    private boolean wasNew(Object entity) {
        Set<Object> inFlight = newInFlight.get();
        if (inFlight == null) {
            return false;
        }
        boolean wasNew = inFlight.remove(entity);
        if (inFlight.isEmpty()) {
            // Unbind rather than leave an empty set on a pooled request thread.
            newInFlight.remove();
        }
        return wasNew;
    }

    /**
     * The entity's own id, by reflection — the reference implementation's approach, and the only one
     * available for a listener typed to {@code Object}.
     *
     * <p>Every {@code @Document} in this service declares {@code getId()}; one that did not would
     * resolve to null here and be skipped with a debug line rather than throw into a write path.
     */
    private String idOf(Object entity) {
        try {
            Method getId = entity.getClass().getMethod("getId");
            Object id = getId.invoke(entity);
            return id == null ? null : id.toString();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * The single {@code _id} a delete query names, if it names one.
     *
     * <p>{@code toString} rather than a cast to {@code String}, for the reason
     * {@link ProfileStatusAnnouncer} records after an afternoon spent on it: the query reaching a
     * listener is the <em>mapped</em> one, and Spring Data converts a 24-character hex id to an
     * {@link ObjectId} on the way through. Matching only {@code String} makes every delete look like a
     * query with no single id.
     */
    private Optional<String> deletedIdIn(Document query) {
        Object id = query == null ? null : query.get("_id");
        return id instanceof String || id instanceof ObjectId ? Optional.of(id.toString()) : Optional.empty();
    }
}
