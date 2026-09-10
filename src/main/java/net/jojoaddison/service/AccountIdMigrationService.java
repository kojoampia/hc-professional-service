package net.jojoaddison.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.jojoaddison.domain.OrphanedAccountRow;
import net.jojoaddison.repository.OrphanedAccountRowRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Moves every stored account key in this database from the gateway login to the gateway's
 * {@code User.id} (backlog.md item 50).
 *
 * <h2>Why this is a service and an admin endpoint rather than an {@code ApplicationRunner}</h2>
 *
 * <p>{@code ShiftTypeMigration} and {@code AngelDutyRoleMigration} beside it are runners, because
 * everything they need is in this database. This one is not: the login-to-{@code User.id} mapping
 * lives in {@code hcProfessionalGateway}'s user store, reachable only over HTTP and only by an
 * administrator since backlog item 55. <b>A startup runner has no caller and therefore no
 * credential</b>, and the alternative — teaching this service to mint a token from the shared signing
 * key — would make a service that only ever validates tokens into one that issues them. So an
 * administrator runs it, and {@link GatewayUserClient} relays that administrator's own token.
 *
 * <h2>Every collection moves in one call, and that is not tidiness</h2>
 *
 * <p>The account key is a <em>lookup</em> key, not a label. The moment {@code profile.account_id}
 * flips for one clinician while {@code professional_application} still holds their login, that
 * clinician's documents 403 and their roster empties — the observation item 53's review made when it
 * rejected a drip-fill. So {@link #TARGETS} is the whole set and one invocation walks all of it.
 *
 * <p><b>What is deliberately not on that list is the audit trail.</b> {@code OnboardingEvent.actor},
 * every {@code created_by} and every {@code last_modified_by} hold the login and go on holding it:
 * they name a person for a person to read and nothing is ever looked up by them. That is also why
 * {@code "system"} — which item 50 names as an unresolvable value — does not arise here at all. It is
 * written by {@code SpringSecurityAuditorAware} and by the {@code actor} defaults, never into a key.
 *
 * <h2>What happens to a row that resolves to nothing</h2>
 *
 * <p>Its key is <b>cleared</b> and the old value is written to {@link OrphanedAccountRow}. See that
 * class for the argument; in short, item 50 forbids synthesising an id and forbids leaving the login
 * in place, and those are the only two other options. A quarantined row belongs to nobody, is refused
 * by every ownership check, and announces no subject on {@code ProfileStatus} — all of which are true
 * statements about it.
 *
 * <h2>Idempotence</h2>
 *
 * <p>A value that is already a known {@code User.id} is left alone, so a second run reports
 * everything as already migrated and writes nothing. That test — <i>is this one of the ids the
 * gateway just gave us</i> — is what makes re-running safe, rather than a marker document that could
 * be true while the data is half-moved. A quarantined row is not re-recorded, because its key is now
 * null and nothing matches it.
 */
@Service
public class AccountIdMigrationService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountIdMigrationService.class);

    /**
     * A Mongo collection and the field in it that holds an account key.
     *
     * @param collection the Mongo collection name, as {@code @Document(collection = ...)} spells it.
     * @param field the stored field name, as {@code @Field(...)} spells it — <b>not</b> the Java
     *     property. {@code messagerecipient.recipient_id} and {@code conversation.created_by} are the
     *     two where the difference bites.
     * @param javaField what to call the field in the report and the quarantine row, so an operator
     *     reading either can find it in the source.
     */
    public record FieldTarget(String collection, String field, String javaField) {}

    /**
     * Every field in this database that holds an account key.
     *
     * <p>Compiled by reading the domain classes rather than by grepping for {@code accountId}: two of
     * the seven are named something else. {@code MessageRecipient.recipientId} is the addressee of a
     * notification and {@code Message.senderId} its author, both written from the caller's identity;
     * {@code Conversation.createdBy} is written from {@code senderId} and shares a name with the
     * auditing field that is <em>not</em> migrated, which is exactly the trap the estate has hit
     * before — a name-based enumeration is short by the members that were named differently.
     */
    static final List<FieldTarget> TARGETS = List.of(
        new FieldTarget("profile", "account_id", "accountId"),
        new FieldTarget("professional_application", "account_id", "accountId"),
        new FieldTarget("device_token", "account_id", "accountId"),
        new FieldTarget("patient_write_receipt", "account_id", "accountId"),
        new FieldTarget("messagerecipient", "recipient_id", "recipientId"),
        new FieldTarget("message", "sender_id", "senderId"),
        new FieldTarget("conversation", "created_by", "createdBy")
    );

    /** The shadow field item 48 added and item 50 folded back into {@code account_id}. */
    static final String RETIRED_ACCOUNT_UID_FIELD = "account_uid";

    /** What one call did, or would do. */
    public record Report(boolean dryRun, int gatewayAccounts, int retiredFieldRows, List<CollectionReport> collections) {
        public int totalRewritten() {
            return collections.stream().mapToInt(CollectionReport::rewritten).sum();
        }

        public int totalQuarantined() {
            return collections.stream().mapToInt(CollectionReport::quarantined).sum();
        }

        /**
         * How many rows were saved from quarantine by {@code profile.account_uid}.
         *
         * <p>Reported because a dry run has to disclose it: the wet run deletes that field from every
         * profile immediately afterwards, so this is the last moment an operator can see how much the
         * migration is leaning on a value that is about to stop existing. The item 50 review found the
         * field being dropped without ever being read, which destroyed a recoverable identity silently.
         */
        public int totalResolvedFromRetiredField() {
            return collections.stream().mapToInt(CollectionReport::resolvedFromRetiredField).sum();
        }

        /** How many profiles still carry {@code account_uid}, all of which the wet run deletes. */
        public int retiredFieldRowsPendingDrop() {
            return retiredFieldRows;
        }
    }

    /**
     * @param alreadyMigrated rows whose key is already a {@code User.id} — everything, on a re-run.
     * @param rewritten rows whose login resolved to an account.
     * @param quarantined rows whose login resolved to nothing; key cleared, value recorded.
     * @param conflicts rows the write itself refused, named. A duplicate key here means two rows
     *     claim one account, which is a data fault a person has to look at — reported rather than
     *     thrown, so the rest of the run still happens and the report still arrives.
     */
    public record CollectionReport(
        String collection,
        String field,
        int examined,
        int alreadyMigrated,
        int rewritten,
        int quarantined,
        int resolvedFromRetiredField,
        List<String> conflicts
    ) {}

    private final MongoTemplate mongoTemplate;
    private final GatewayUserClient gatewayUserClient;
    private final OrphanedAccountRowRepository orphanedAccountRowRepository;

    public AccountIdMigrationService(
        MongoTemplate mongoTemplate,
        GatewayUserClient gatewayUserClient,
        OrphanedAccountRowRepository orphanedAccountRowRepository
    ) {
        this.mongoTemplate = mongoTemplate;
        this.gatewayUserClient = gatewayUserClient;
        this.orphanedAccountRowRepository = orphanedAccountRowRepository;
    }

    /**
     * Reads the gateway's user table and moves every account key onto it.
     *
     * @param dryRun when true nothing is written — the report says what would happen. The endpoint
     *     defaults to this, because the run clears fields and an operator should see the quarantine
     *     count before it does.
     * @throws GatewayUserClient.GatewayUnavailableException if the mapping could not be read in full.
     *     Nothing is written in that case: a partial mapping would quarantine rows that have owners.
     */
    public Report migrate(boolean dryRun) {
        Map<String, String> loginToAccountId = gatewayUserClient.loginToAccountId();
        Set<String> knownAccountIds = new HashSet<>(loginToAccountId.values());
        LOG.info("accountId migration starting (dryRun={}) against {} gateway accounts", dryRun, loginToAccountId.size());

        // Counted before anything is written, because the wet run deletes this field and a dry run has
        // to be able to say how many rows are about to lose it. See Report.retiredFieldRowsPendingDrop.
        int retiredFieldRows = (int) mongoTemplate.count(new Query(Criteria.where(RETIRED_ACCOUNT_UID_FIELD).exists(true)), "profile");

        List<CollectionReport> collections = new ArrayList<>();
        for (FieldTarget target : TARGETS) {
            collections.add(migrateOne(target, loginToAccountId, knownAccountIds, dryRun));
        }
        if (!dryRun) {
            dropRetiredAccountUid();
        }
        Report report = new Report(dryRun, loginToAccountId.size(), retiredFieldRows, collections);
        LOG.info(
            "accountId migration finished (dryRun={}): {} rewritten ({} of them via the retired account_uid), " +
            "{} quarantined, {} row(s) carried account_uid and it is dropped on a wet run",
            dryRun,
            report.totalRewritten(),
            report.totalResolvedFromRetiredField(),
            report.totalQuarantined(),
            report.retiredFieldRowsPendingDrop()
        );
        return report;
    }

    private CollectionReport migrateOne(
        FieldTarget target,
        Map<String, String> loginToAccountId,
        Set<String> knownAccountIds,
        boolean dryRun
    ) {
        Query query = new Query(Criteria.where(target.field()).ne(null));
        List<Document> rows = mongoTemplate.find(query, Document.class, target.collection());

        int examined = 0;
        int alreadyMigrated = 0;
        int rewritten = 0;
        int quarantined = 0;
        int resolvedFromRetiredField = 0;
        List<String> conflicts = new ArrayList<>();

        for (Document row : rows) {
            Object raw = row.get(target.field());
            if (!(raw instanceof String value) || value.isBlank()) {
                continue;
            }
            examined++;
            if (knownAccountIds.contains(value)) {
                // Already a User.id. This is what makes a second run a no-op, and it is a test on the
                // value rather than a marker document — a marker can be true while the data is not.
                alreadyMigrated++;
                continue;
            }
            String resolved = loginToAccountId.get(value.toLowerCase(Locale.ROOT));
            if (resolved == null) {
                // Before giving up on a row, ask the field item 48 added for exactly this value.
                // `account_uid` was only ever written by `upsertOwnProfile` from the caller's own
                // issuer-checked `uid` claim, so where it is present it holds a `User.id` this
                // gateway minted for this profile's owner — which is what item 50 is looking for.
                //
                // It matters in the one case the rest of this method cannot serve: a login renamed
                // after the profile's last save. Then `account_id` holds a login the gateway no
                // longer knows, and without this the row is quarantined while `dropRetiredAccountUid`
                // deletes the only field that could still have resolved it. The item 50 review found
                // that; it destroyed a recoverable identity and told nobody, which is worse than the
                // quarantine it looked like.
                //
                // Still checked against `knownAccountIds`: a stale `account_uid` naming a deleted
                // account is not a resolution, and trusting it unchecked would be the synthesis item
                // 50 forbids.
                Object carried = row.get(RETIRED_ACCOUNT_UID_FIELD);
                if (carried instanceof String uid && !uid.isBlank() && knownAccountIds.contains(uid)) {
                    resolved = uid;
                    resolvedFromRetiredField++;
                }
            }
            String documentId = String.valueOf(row.get("_id"));
            try {
                if (resolved != null) {
                    if (!dryRun) {
                        mongoTemplate.updateFirst(
                            new Query(Criteria.where("_id").is(row.get("_id"))),
                            new Update().set(target.field(), resolved),
                            target.collection()
                        );
                    }
                    rewritten++;
                } else {
                    if (!dryRun) {
                        quarantine(
                            target,
                            row.get("_id"),
                            value,
                            row.get(RETIRED_ACCOUNT_UID_FIELD) instanceof String carriedUid ? carriedUid : null
                        );
                    }
                    quarantined++;
                }
            } catch (RuntimeException e) {
                // Named, not swallowed, and not thrown either: the commonest cause is the unique
                // sparse index on profile.account_id refusing a second row for one account, which is
                // a fault about those two rows and says nothing about the other five collections.
                conflicts.add(target.collection() + "/" + documentId + ": " + e.getMessage());
                LOG.warn("accountId migration could not move {}/{}", target.collection(), documentId, e);
            }
        }
        return new CollectionReport(
            target.collection(),
            target.javaField(),
            examined,
            alreadyMigrated,
            rewritten,
            quarantined,
            resolvedFromRetiredField,
            conflicts
        );
    }

    /**
     * Clears the key and records what it held.
     *
     * <p>In that order in the code and in that order in the argument: the record is written first so
     * a failure between the two leaves the value readable rather than gone.
     */
    private void quarantine(FieldTarget target, Object documentId, String orphanedValue, String carriedAccountUid) {
        String documentKey = String.valueOf(documentId);
        if (
            orphanedAccountRowRepository
                .findByCollectionNameAndDocumentIdAndFieldName(target.collection(), documentKey, target.javaField())
                .isEmpty()
        ) {
            orphanedAccountRowRepository.save(
                new OrphanedAccountRow()
                    .collectionName(target.collection())
                    .documentId(documentKey)
                    .fieldName(target.javaField())
                    .orphanedValue(orphanedValue)
                    // Recorded even though it did not resolve, because `dropRetiredAccountUid` is about
                    // to delete it and this row is the only place it would survive. A stale value naming
                    // a deleted account is still evidence for whoever reconciles this by hand — it says
                    // *which* account, which the dead login may no longer.
                    .carriedAccountUid(carriedAccountUid)
                    .detectedAt(Instant.now())
            );
        }
        // The raw `_id`, not its `String` form. Spring's QueryMapper converts a 24-hex string to an
        // ObjectId for `_id`, which is why the ObjectId case passed — but a document whose `_id` is a
        // stored String that happens to be 24-hex would be converted, match nothing, and the unset
        // would report success having changed no row. Found by the item 50 review.
        var result = mongoTemplate.updateFirst(
            new Query(Criteria.where("_id").is(documentId)),
            new Update().unset(target.field()),
            target.collection()
        );
        if (result.getMatchedCount() != 1) {
            throw new IllegalStateException(
                "quarantining " + target.collection() + "/" + documentKey + " matched " + result.getMatchedCount() + " rows, not 1"
            );
        }
    }

    /**
     * Removes {@code profile.account_uid}, which item 48 added to carry the {@code User.id} beside a
     * login-valued {@code account_id} and item 50 made redundant by putting that value in the field
     * the estate names. Unset rather than left in place: Spring Data ignores an unmapped key, so it
     * would sit there indefinitely as a second copy of the join key that this change exists to remove
     * — and the next person to read the collection would reasonably wonder which one was authoritative.
     */
    private void dropRetiredAccountUid() {
        var result = mongoTemplate.updateMulti(
            new Query(Criteria.where(RETIRED_ACCOUNT_UID_FIELD).exists(true)),
            new Update().unset(RETIRED_ACCOUNT_UID_FIELD),
            "profile"
        );
        if (result.getModifiedCount() > 0) {
            LOG.info("Dropped the retired profile.{} from {} row(s)", RETIRED_ACCOUNT_UID_FIELD, result.getModifiedCount());
        }
    }
}
