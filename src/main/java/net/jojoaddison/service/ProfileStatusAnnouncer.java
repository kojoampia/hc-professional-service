package net.jojoaddison.service;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfileRepository;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NamedThreadLocal;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;
import org.springframework.data.mongodb.core.mapping.event.BeforeDeleteEvent;
import org.springframework.stereotype.Component;

/**
 * The one place {@code ProfileStatus} is announced from (backlog.md item 49).
 *
 * <p><b>Why this is a listener and not four more calls.</b> Item 47 § 2b hung the announcement off a
 * table of four call sites, and item 49 is the bill for it: {@code POST /api/onboarding/documents} —
 * the path a clinician renews their own licence by — was not on the table, and neither was any of
 * the {@code PersonalDocumentResource} CRUD surface. An upload adds a {@code PENDING} row, so
 * {@code isVerified} went true → false on the server while hc-admin's directory went on rendering
 * "verified". <b>A list of call sites cannot fail when a fifth one is written</b>, which is the whole
 * defect, so the mechanism had to stop being a list. Every announcement now comes from here, and the
 * old calls in {@code OnboardingService} and {@code ProfileResource} are gone rather than kept
 * alongside.
 *
 * <p><b>Where the outcome is decided is the write, not the handler.</b> Nothing in this service can
 * move {@code isComplete}, {@code isVerified}, {@code modifiedDate} or {@code lastModifiedBy} without
 * persisting a {@link Profile}, a {@link PersonalDocument} or a {@link ProfessionalApplication} —
 * the first two directly, the third because the consent requirement {@code isComplete} counts lives
 * on the application. Spring Data raises {@code AfterSaveEvent} for every one of those saves however
 * it was reached, so a resource, a service, a scheduler or a consumer written next month publishes
 * without its author knowing this class exists.
 *
 * <p><b>One frame per request, computed after the request's last write.</b> Announcements are
 * collected in a set and flushed by {@code ProfileStatusAnnouncementFilter}, deduplicated by profile
 * id. That is not only about volume: a superseding upload saves twice — the replacement, then the
 * archived row — and a frame sent between the two would report an {@code isVerified} that was never
 * true of any moment the caller asked for. Off a request thread (the compliance scheduler, a Kafka
 * consumer, a startup runner) there is no such boundary, so each save announces immediately.
 *
 * <p>The boundary is a filter of this mechanism's own rather than {@code RequestContextHolder},
 * deliberately: that holder is bound by whoever happens to have bound it — Spring's
 * {@code ServletTestExecutionListener} binds one around a whole test method, outside any request —
 * so a batch keyed on it would be opened by something that will never close it, and the
 * announcement would be deferred for ever. A mechanism that decides when to publish should not
 * infer its own unit of work from a third party's.
 *
 * <p><b>Publishing must never break the write path.</b> The row is persisted by the time any of this
 * runs, and the reads the announcement makes can fail as readily as the send can, so everything here
 * is guarded — {@code DomainEventPublisher} catches its own broker failures and cannot catch a
 * repository throwing on the way in.
 *
 * <h2>What this deliberately does not cover</h2>
 *
 * <ul>
 *   <li><b>Query-based updates.</b> {@code MongoTemplate.updateFirst}/{@code updateMulti}/
 *       {@code findAndModify} raise no save event, by Spring Data's design — there is no entity to
 *       hand a listener. Nothing writes the three documents that way today ({@code ShiftTypeMigration}
 *       is the only such caller and it rewrites {@code DutyRoster}); a future one would be silent, and
 *       {@code ProfileStatusOnEveryWriteIT} names that.</li>
 *   <li><b>Deleting a {@code Profile}.</b> Deleting a {@code PersonalDocument} is announced, because
 *       the owning profile survives and its {@code isVerified} may have moved. A deleted profile has
 *       no such snapshot to send: {@code ProfileStatus}'s seven fields are fixed and none of them can
 *       say "gone", and publishing the last known state would assert the opposite of what happened.
 *       Telling a directory about a deletion needs a field or an event type this contract does not
 *       have.</li>
 *   <li><b>{@code entity.created}.</b> {@code ProfileService.updatePushPreferences} creates a profile
 *       when the account has none and announces no creation either — item 49 names that too. It is
 *       left alone: {@code entity.created} carries an {@code actor} and an {@code accountId} that
 *       differ per entity type and are not derivable from the saved document, it is published by hand
 *       from ten resources, and it rides {@code hc.professional.entity}, which hc-admin is not
 *       subscribed to. The profile's arrival is not lost — this class announces its
 *       {@code ProfileStatus}, which is the frame the directory actually reads.</li>
 *   <li><b>Writes made outside this application</b> — mongosh, a restore, another service against the
 *       same database. There is no change stream here.</li>
 * </ul>
 */
@Component
public class ProfileStatusAnnouncer extends AbstractMongoEventListener<Object> {

    private static final Logger log = LoggerFactory.getLogger(ProfileStatusAnnouncer.class);

    /**
     * The profile ids this thread's unit of work still owes an announcement, or {@code null} when
     * there is no unit of work — a scheduler, a consumer, a startup runner.
     */
    private final ThreadLocal<Set<String>> pending = new NamedThreadLocal<>("ProfileStatus announcements");

    private final ProfileRepository profileRepository;
    private final PersonalDocumentRepository personalDocumentRepository;
    private final OnboardingService onboardingService;

    public ProfileStatusAnnouncer(
        ProfileRepository profileRepository,
        PersonalDocumentRepository personalDocumentRepository,
        OnboardingService onboardingService
    ) {
        this.profileRepository = profileRepository;
        this.personalDocumentRepository = personalDocumentRepository;
        this.onboardingService = onboardingService;
    }

    /**
     * Every persisted document, of every type — the generic parameter is {@code Object} on purpose so
     * that adding a fourth type here is an edit to {@link #profileIdOf} rather than a new listener
     * someone has to remember to register.
     */
    @Override
    public void onAfterSave(AfterSaveEvent<Object> event) {
        try {
            record(profileIdOf(event.getSource()));
        } catch (RuntimeException e) {
            log.error("Could not queue a ProfileStatus announcement after a save — the write it followed stands", e);
        }
    }

    /**
     * Deleting a document can clear the last unverified row on a profile, or the last verified one.
     *
     * <p>Resolved <em>before</em> the delete because afterwards there is nothing left to read the
     * profile id off; the announcement itself still happens at flush, by which time the row is gone
     * and the snapshot is the true one. A query that names no single {@code _id} — {@code deleteAll},
     * a criteria delete — is skipped rather than guessed at.
     */
    @Override
    public void onBeforeDelete(BeforeDeleteEvent<Object> event) {
        try {
            if (!PersonalDocument.class.equals(event.getType())) {
                return;
            }
            deletedIdIn(event.getDocument())
                .flatMap(personalDocumentRepository::findById)
                .map(PersonalDocument::getProfileId)
                .ifPresent(this::record);
        } catch (RuntimeException e) {
            log.error("Could not queue a ProfileStatus announcement before a delete — the delete stands", e);
        }
    }

    /**
     * The profile whose published state this saved document can have moved, or {@code null} if it
     * cannot have moved one.
     *
     * <p>{@code ProfessionalApplication} is here for a reason that is easy to miss: {@code isComplete}
     * counts eight requirements and the first of them, consent, is
     * {@code ProfessionalApplication.consentAcceptedAt}. So starting an application moves a published
     * field without touching either of the other two collections.
     */
    private String profileIdOf(Object entity) {
        if (entity instanceof Profile profile) {
            return profile.getId();
        }
        if (entity instanceof PersonalDocument document) {
            return document.getProfileId();
        }
        if (entity instanceof ProfessionalApplication application) {
            return profileIdOf(application);
        }
        return null;
    }

    private String profileIdOf(ProfessionalApplication application) {
        if (application.getProfileId() != null) {
            return application.getProfileId();
        }
        // An application started before its profile exists names no profile yet; the account is the
        // only link there is, and most of the time it resolves to nothing, which is correct.
        return application.getAccountId() == null
            ? null
            : profileRepository.findByAccountId(application.getAccountId()).map(Profile::getId).orElse(null);
    }

    /**
     * The single {@code _id} a delete query names, if it names one.
     *
     * <p>{@code toString} rather than a cast to {@code String}: the query reaching a listener is the
     * <em>mapped</em> one, and Spring Data converts a 24-character hex id to an {@link ObjectId} on
     * the way through, so the value here is an {@code ObjectId} for every document Mongo generated
     * an id for and a {@code String} for the rest. Matching only the second was worth an afternoon —
     * every delete looked like a query with no single id and was skipped.
     */
    private Optional<String> deletedIdIn(Document query) {
        Object id = query == null ? null : query.get("_id");
        return id instanceof String || id instanceof ObjectId ? Optional.of(id.toString()) : Optional.empty();
    }

    /**
     * Begins collecting instead of announcing, for the duration of one request.
     *
     * @return whether this call opened the unit of work, and so owes the matching
     *     {@link #flush()}. False when one is already open — an ERROR dispatch re-enters the filter
     *     on the same thread, and the outer frame is the one that should decide when the request is
     *     over.
     */
    public boolean open() {
        if (pending.get() != null) {
            return false;
        }
        pending.set(new LinkedHashSet<>());
        return true;
    }

    /** Announces what the unit of work collected, once per profile, and closes it. */
    public void flush() {
        Set<String> collected = pending.get();
        // Cleared before anything is sent: an announcement that threw past its own guard must not
        // leave a set bound to a pooled request thread for the next request to inherit.
        pending.remove();
        if (collected != null) {
            collected.forEach(this::announce);
        }
    }

    private void record(String profileId) {
        if (profileId == null) {
            return;
        }
        Set<String> collected = pending.get();
        if (collected == null) {
            // No unit of work to defer to — announce now rather than never. The cost is one frame per
            // save on those paths instead of one per unit of work, which is the right way round: an
            // extra snapshot is harmless and a missing one is item 49.
            announce(profileId);
            return;
        }
        collected.add(profileId);
    }

    private void announce(String profileId) {
        try {
            onboardingService.publishProfileStatusFor(profileId);
        } catch (RuntimeException e) {
            log.error("Could not announce ProfileStatus for profile {} — the write it followed stands", profileId, e);
        }
    }
}
