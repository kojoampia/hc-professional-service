package net.jojoaddison.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.Sex;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Reconciles stored free text in {@code profile.sex} and {@code profile.card_type} with the
 * enumerations those fields now are (F9).
 *
 * <h2>Why a migration is needed at all</h2>
 *
 * <p>Both fields were {@code String} and unconstrained, so {@code &#123;"sex":"banana"&#125;} and
 * {@code &#123;"cardType":"loyalty card"&#125;} stored and answered 200. {@link Sex} and
 * {@link DocumentType} are the types now, and <b>a stored value outside the enumeration is a row
 * the mapper cannot read</b>: a {@code GET} of it fails on conversion rather than returning a
 * profile. Leaving it would be the estate's recurring failure — something that reads as a defect in
 * whatever code touches the row next.
 *
 * <h2>⛔ What it does with a value, and the one judgement in it</h2>
 *
 * <table>
 *   <caption>The three cases</caption>
 *   <tr><th>stored</th><th>action</th></tr>
 *   <tr><td>exactly a member name — {@code "FEMALE"}, {@code "GHANACARD"}</td>
 *       <td>left alone; it is already the enum</td></tr>
 *   <tr><td>a member name but for case or surrounding space — {@code "female"}</td>
 *       <td><b>normalised</b> to the member, and the old value recorded</td></tr>
 *   <tr><td>anything else — {@code "banana"}, {@code "loyalty card"}</td>
 *       <td><b>quarantined</b>: the key is unset and the old value recorded</td></tr>
 * </table>
 *
 * <p><b>Quarantine, not a mapping and not a silent null</b>, which is the house pattern —
 * {@code orphaned_account_row}, written by {@code AccountIdMigrationService} for a profile whose
 * login resolved to nothing. The old value goes to {@code orphaned_profile_enum_value} with the
 * profile's {@code _id}, the field, the value and the action taken, for somebody to reconcile.
 * Inventing a mapping (is {@code "loyalty card"} an {@code NHIS} card?) would manufacture a fact a
 * clinician did not state, and nulling it without a record would discard something they typed with
 * nothing anywhere to recover it from.
 *
 * <p>⚠ <b>The case-only row is the judgement, and it is recorded rather than buried.</b>
 * {@code "female"} and {@code FEMALE} are the same answer differently cased, so normalising is not
 * inventing a mapping — but it <em>is</em> a decision, so it writes the same record the quarantine
 * does. The alternative, quarantining it, would throw away a value a clinician entered over nothing
 * but capitalisation, which the owner's instruction also forbids. The quality database held
 * <b>exactly one</b> such row when this landed ({@code sex: "female"}) and no unmappable value at
 * all; its one {@code card_type} was {@code "GHANACARD"}, already a member.
 *
 * <h2>Idempotent, by construction rather than by a flag</h2>
 *
 * <p>It reads only rows whose stored value is not already a member name, so a second run matches
 * nothing. There is no marker document and no "has this run" flag that could be wrong; deployments
 * roll forward and restart freely. Raw {@link Document}s through {@link MongoTemplate} and
 * necessarily so — reading these rows through the mapped {@code Profile} is exactly what fails until
 * they are fixed, the same reason {@code EmergencyContactListMigration} beside it does.
 *
 * <p><b>Excluded from {@code testdev} and {@code testprod}</b>, the two profiles {@code pom.xml}
 * activates for an integration-test run, exactly as its two neighbours are; the reasoning for the
 * expression (and for why it is not {@code !test}) is recorded on {@code ShiftTypeMigration}.
 * Spring Boot does not invoke {@link ApplicationRunner} beans under {@code @SpringBootTest}, so
 * {@code ProfileEnumValueMigrationIT} constructs this class and calls {@link #run} itself.
 */
@Component
@Profile("!testdev & !testprod")
public class ProfileEnumValueMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProfileEnumValueMigration.class);

    private static final String COLLECTION = "profile";

    /** Where a value this migration could not place is recorded, in the shape of {@code orphaned_account_row}. */
    static final String QUARANTINE_COLLECTION = "orphaned_profile_enum_value";

    /** The stored key of each retyped field, against the member names it may now hold. */
    private static final Map<String, List<String>> ENUM_FIELDS = Map.of(
        "sex",
        java.util.Arrays.stream(Sex.values()).map(Enum::name).toList(),
        "card_type",
        java.util.Arrays.stream(DocumentType.values()).map(Enum::name).toList()
    );

    private final MongoTemplate mongoTemplate;

    public ProfileEnumValueMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        ENUM_FIELDS.forEach(this::reconcile);
    }

    private void reconcile(String field, List<String> members) {
        // Everything that is NOT already a member name. `$nin` on the member list rather than a
        // regex, so the query says the same thing the enum does and nothing has to be kept in step.
        Query pending = new Query(Criteria.where(field).exists(true).ne(null).nin(members));
        List<Document> rows = mongoTemplate.find(pending, Document.class, COLLECTION);
        if (rows.isEmpty()) {
            log.debug("Every stored {}.{} is already an enum member — nothing to reconcile", COLLECTION, field);
            return;
        }

        List<String> normalised = new ArrayList<>();
        List<String> quarantined = new ArrayList<>();
        for (Document row : rows) {
            Object stored = row.get(field);
            String value = stored == null ? null : stored.toString();
            Optional<String> member = memberFor(value, members);
            Update update = member.map(name -> new Update().set(field, name)).orElseGet(() -> new Update().unset(field));
            String action = member.isPresent() ? "normalised" : "quarantined";

            // The record goes in BEFORE the row changes. A crash between the two leaves a readable
            // note about a row still holding its old value, which is recoverable; the other order
            // loses the value with nothing saying it ever existed.
            mongoTemplate.insert(
                new org.bson.Document()
                    .append("collection", COLLECTION)
                    .append("documentId", row.get("_id"))
                    .append("field", field)
                    .append("oldValue", value)
                    .append("newValue", member.orElse(null))
                    .append("action", action)
                    .append("at", Instant.now()),
                QUARANTINE_COLLECTION
            );
            mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(row.get("_id"))), update, COLLECTION);
            (member.isPresent() ? normalised : quarantined).add(value);
        }

        if (!normalised.isEmpty()) {
            log.info("Normalised {} {}.{} value(s) differing only by case or space: {}", normalised.size(), COLLECTION, field, normalised);
        }
        if (!quarantined.isEmpty()) {
            log.warn(
                "Quarantined {} {}.{} value(s) outside the enumeration into {} for reconciliation: {}",
                quarantined.size(),
                COLLECTION,
                field,
                QUARANTINE_COLLECTION,
                quarantined
            );
        }
    }

    /** The member this value is, ignoring case and surrounding space, or empty if it is none of them. */
    private static Optional<String> memberFor(String value, List<String> members) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String candidate = value.trim().toUpperCase(Locale.ROOT);
        return members.stream().filter(candidate::equals).findFirst();
    }
}
