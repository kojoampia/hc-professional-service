package net.jojoaddison.config;

import java.util.List;
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
 * Carries {@code professional_application}'s two retired keys onto the names {@code profile.md}
 * step 4 gives them (T3).
 *
 * <pre>
 *   { requested_role: "ROLE_NURSE", consent_accepted_at: ISODate(…) }
 *      -&gt;
 *   { authority:      "ROLE_NURSE", agreed_date:         ISODate(…), agreed: true }
 * </pre>
 *
 * <h2>It REMAPS rather than deletes, and {@code EmergencyContactListMigration} is the precedent</h2>
 *
 * <p>The two shapes beside this one answer the same question in opposite directions.
 * {@code AngelDutyRoleMigration} <b>deletes</b> the rows it cannot rename, because mapping an
 * angel's shift onto {@code CARER} would invent a cover arrangement nobody made.
 * {@code ShiftTypeMigration} and {@code EmergencyContactListMigration} <b>rewrite</b>, because a
 * shift that was worked and a next of kin who was named are facts wanting a new name.
 *
 * <p><b>Consent is firmly the second kind, and the cost of getting it wrong is not cosmetic.</b>
 * Somebody ticked a box and a timestamp records when; that is a legal fact about processing their
 * identity documents and it does not stop being true because the field was renamed. Dropping it
 * would un-satisfy the {@code consent} requirement for every applicant who had given it — and
 * {@code OnboardingService.markStatus} refuses {@code ACTIVE} unless every requirement is
 * satisfied, so the visible consequence would be blocked activations and clinicians asked to
 * consent twice, not a meter reading low.
 *
 * <h2>⚠ {@code agreed} is derived from the date, which is the one judgement here</h2>
 *
 * <p>{@code agreed} did not exist before T3, so no stored row carries it and there is nothing to
 * copy. It is set to {@code true} <b>exactly where a consent date is stored and the boolean is
 * not</b>, because that date could only ever have been written by
 * {@code OnboardingService.startApplication}, which refuses with 400 unless consent was accepted —
 * so the date's presence <em>is</em> the record of the tick, and the derivation asserts nothing the
 * old schema did not already hold.
 *
 * <p>A row with no date at all is left with {@code agreed} absent, which maps to {@code false}: no
 * such row should exist, since the only writer stamps the date in the same statement that creates
 * the document, but inventing a {@code true} for one would be the opposite of the above — asserting
 * a consent nothing records.
 *
 * <p>It runs as a <b>separate pass</b> over {@code agreed_date} rather than as a clause of the
 * rename, which is not tidiness: the derivation is then stated once, over the field it is actually
 * about, and it catches a row whose date arrived by either route — this migration, or the new code
 * writing {@code agreed_date} directly.
 *
 * <h2>⭐ A row carrying BOTH names, which is where the first version of this class was wrong</h2>
 *
 * <p>Reachable in practice rather than theoretical: a database restored from a backup taken mid-run
 * can hold a legacy key and its replacement at once, and <b>the newer field has to win</b>. The
 * first version matched only rows that did <em>not</em> have the new key — copying
 * {@code EmergencyContactListMigration}'s query — which does win the right value and then
 * <b>leaves the stale key in the document for ever</b>, because no later run will ever match it
 * again.
 *
 * <p>So the match is on the legacy key alone and the two effects are separated: the value is copied
 * <em>only</em> when the target is absent, and the legacy key is unset <em>always</em>. A second
 * source of truth that nothing reads is how this estate's own notes describe two correct-for-now
 * copies becoming one wrong one.
 *
 * <h2>Idempotent, and by the same construction as its neighbours</h2>
 *
 * <p>Each pass matches on the key it then removes or fills, so a second run matches nothing. There
 * is no marker document and no "has this run" flag that could be wrong; deployments roll forward and
 * restart freely.
 *
 * <p>Raw {@link Document}s through {@link MongoTemplate} rather than mapped
 * {@code ProfessionalApplication}s, necessarily: the entity no longer declares the keys this reads,
 * so the mapper would drop them on the way in and the migration would have nothing to copy. A
 * migration cannot be expressed in the vocabulary it exists to leave behind.
 *
 * <p>There is no migration framework in this repo — no Liquibase, no Mongock — so this runs as an
 * {@link ApplicationRunner}, in the shape of {@link ShiftTypeMigration} beside it.
 *
 * <p><b>Excluded from {@code testdev} and {@code testprod}</b> — the two profiles {@code pom.xml}
 * activates for an integration-test run. The expression is {@code ShiftTypeMigration}'s and the
 * reasoning is recorded there at length, including why it is not {@code !test}: the Spring profile
 * {@code test} is never active under {@code ./mvnw verify}, so {@code !test} excludes nothing from a
 * build while it does exclude a {@code dev,test} deployment — which cost hc-admin an outage.
 *
 * <p>Spring Boot does not invoke {@link ApplicationRunner} beans under {@code @SpringBootTest} — it
 * is {@code SpringApplication.run} that calls them — so
 * {@code ProfessionalApplicationConsentMigrationIT} constructs this class directly and calls
 * {@link #run(ApplicationArguments)} itself, and the same test pins the profile registration.
 */
@Component
@Profile("!testdev & !testprod")
public class ProfessionalApplicationConsentMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProfessionalApplicationConsentMigration.class);

    private static final String COLLECTION = "professional_application";

    /** The retired role key. */
    static final String LEGACY_ROLE = "requested_role";

    /** The name {@code profile.md} step 4 gives it. */
    static final String AUTHORITY = "authority";

    /** The retired consent timestamp. */
    static final String LEGACY_CONSENT_AT = "consent_accepted_at";

    /** The name {@code profile.md} step 4 gives it. */
    static final String AGREED_DATE = "agreed_date";

    /** The boolean step 4 adds; derived from {@link #AGREED_DATE}. See the class note. */
    static final String AGREED = "agreed";

    private final MongoTemplate mongoTemplate;

    public ProfessionalApplicationConsentMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        long roles = rename(LEGACY_ROLE, AUTHORITY);
        long consents = rename(LEGACY_CONSENT_AT, AGREED_DATE);
        long ticks = deriveAgreedFromTheDate();
        if (roles > 0 || consents > 0 || ticks > 0) {
            log.info(
                "Migrated {} {} and {} {} on {}, and derived {} {} flag(s)",
                roles,
                LEGACY_ROLE,
                consents,
                LEGACY_CONSENT_AT,
                COLLECTION,
                ticks,
                AGREED
            );
        } else {
            log.debug("No legacy consent or role keys found on {} — nothing to migrate", COLLECTION);
        }
    }

    /**
     * Moves {@code legacy} onto {@code target}, and removes {@code legacy} either way.
     *
     * <p>⚠ <b>The two effects are deliberately not conditional together</b> — see the class note on
     * a row carrying both names. Matching only rows without the target wins the right value and
     * leaves the stale key behind for ever, because nothing matches it again.
     *
     * @return how many rows were rewritten.
     */
    private long rename(String legacy, String target) {
        List<Document> rows = mongoTemplate.find(new Query(Criteria.where(legacy).exists(true)), Document.class, COLLECTION);
        long migrated = 0;
        for (Document row : rows) {
            Object value = row.get(legacy);
            Update update = new Update().unset(legacy);
            // Copied only where there is nothing newer to overwrite, and only when there is
            // something to copy: a key present but null carries no value, so it is dropped without
            // writing a target. Never seen; stated so the guard does not read as defensive clutter.
            if (value != null && !row.containsKey(target)) {
                update.set(target, value);
            }
            mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(row.get("_id"))), update, COLLECTION);
            migrated++;
        }
        return migrated;
    }

    /**
     * Sets {@link #AGREED} wherever a consent date is stored and the boolean is not.
     *
     * <p>The class note carries the argument for why the date's presence is the record of the tick.
     * Run after {@link #rename}, so it sees a date that arrived from {@link #LEGACY_CONSENT_AT} as
     * well as one the new code wrote directly.
     *
     * @return how many flags were derived.
     */
    private long deriveAgreedFromTheDate() {
        Query pending = new Query(Criteria.where(AGREED_DATE).exists(true).and(AGREED).exists(false));
        return mongoTemplate.updateMulti(pending, new Update().set(AGREED, true), COLLECTION).getModifiedCount();
    }
}
