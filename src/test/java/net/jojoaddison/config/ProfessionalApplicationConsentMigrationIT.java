package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * {@link ProfessionalApplicationConsentMigration} — {@code requested_role} becomes
 * {@code authority}, {@code consent_accepted_at} becomes {@code agreed_date}, and {@code agreed} is
 * derived from the second (profile.md step 4, T3).
 *
 * <p>This constructs the runner and calls it directly, for the reason
 * {@code EmergencyContactListMigrationIT} records: Spring Boot does not invoke
 * {@link org.springframework.boot.ApplicationRunner} beans under {@code @SpringBootTest} — it is
 * {@code SpringApplication.run} that calls them — so the bean in the context never fires here
 * whatever profile it registers on.
 *
 * <p><b>The fixtures are raw {@link Document}s and necessarily so.</b> The entity no longer declares
 * the keys this migration reads, so a fixture built through {@code ProfessionalApplication} would
 * store nothing to migrate and every case here would pass for nothing. A migration cannot be set up
 * in the vocabulary it replaces.
 *
 * <p>⚠ <b>The entity read-back is the assertion that matters most.</b> Asserting the BSON alone
 * would pass against a shape Spring Data cannot read — and the consequence of getting <em>that</em>
 * wrong is not a null field: {@code OnboardingService.progressFor} reads {@code agreed} for the
 * {@code consent} requirement and {@code markStatus} refuses {@code ACTIVE} unless every requirement
 * is satisfied, so a migration that moved the key but not in a way the mapper reads would block
 * activations and ask clinicians to consent twice.
 */
@IntegrationTest
class ProfessionalApplicationConsentMigrationIT {

    private static final String COLLECTION = "professional_application";

    private static final Instant CONSENTED_AT = Instant.parse("2026-03-04T09:15:00Z");

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ProfessionalApplicationRepository applicationRepository;

    private ProfessionalApplicationConsentMigration migration;

    @BeforeEach
    void setUp() {
        mongoTemplate.remove(new Query(), COLLECTION);
        migration = new ProfessionalApplicationConsentMigration(mongoTemplate);
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.remove(new Query(), COLLECTION);
    }

    /** A document in the shape the collection held before T3. */
    private void insertLegacy(String accountId) {
        mongoTemplate.insert(
            new Document("account_id", accountId)
                .append("login", accountId)
                .append("status", ProfileStatus.CREDENTIAL_REVIEW.name())
                .append("requested_role", "ROLE_NURSE")
                .append("consent_accepted_at", CONSENTED_AT),
            COLLECTION
        );
    }

    private Document raw(String accountId) {
        return mongoTemplate.findOne(new Query(Criteria.where("account_id").is(accountId)), Document.class, COLLECTION);
    }

    /**
     * ⭐ The whole of it: both values land on the new keys, both old keys are gone, and the boolean
     * step 4 adds is derived.
     */
    @Test
    void carriesBothLegacyKeysOntoTheNamesStepFourGives() {
        insertLegacy("legacy-nurse");

        migration.run(null);

        Document row = raw("legacy-nurse");
        assertThat(row.get("authority")).isEqualTo("ROLE_NURSE");
        assertThat(row.get("agreed_date")).isEqualTo(java.util.Date.from(CONSENTED_AT));
        assertThat(row.get("agreed")).isEqualTo(true);
        assertThat(row).doesNotContainKeys("requested_role", "consent_accepted_at");
    }

    /**
     * ⛔ <b>And the migrated row reads back through the entity</b>, which is the half the BSON
     * assertions above cannot see. See the class note on what a mapper-invisible key would cost.
     */
    @Test
    void theMigratedRowIsReadableThroughTheEntity() {
        insertLegacy("legacy-readable");

        migration.run(null);

        ProfessionalApplication application = applicationRepository.findByAccountId("legacy-readable").orElseThrow();
        assertThat(application.getAuthority()).isEqualTo("ROLE_NURSE");
        assertThat(application.getAgreedDate()).isEqualTo(CONSENTED_AT);
        assertThat(application.isAgreed()).isTrue();
    }

    /**
     * ⚠ <b>The derived boolean is derived from the consent timestamp and from nothing else.</b>
     *
     * <p>A row carrying only the retired role key gets {@code authority} and <em>not</em>
     * {@code agreed}: no stored field records a tick there, and claiming one would assert a consent
     * nothing holds — the opposite of what the rest of this migration exists to preserve.
     */
    @Test
    void aRowWithNoConsentTimestampIsNotClaimedAsConsented() {
        mongoTemplate.insert(
            new Document("account_id", "legacy-roleonly")
                .append("status", ProfileStatus.APPLICATION_STARTED.name())
                .append("requested_role", "ROLE_CARER"),
            COLLECTION
        );

        migration.run(null);

        Document row = raw("legacy-roleonly");
        assertThat(row.get("authority")).isEqualTo("ROLE_CARER");
        assertThat(row).doesNotContainKey("agreed");
        assertThat(applicationRepository.findByAccountId("legacy-roleonly").orElseThrow().isAgreed()).isFalse();
    }

    /**
     * ⚠ <b>And the derivation is a pass over {@code agreed_date}, not a clause of the rename</b> —
     * so a row whose date was written directly by the new code, with no legacy key anywhere near it,
     * still gets the boolean.
     *
     * <p>That case is reachable: {@code agreed} and {@code agreed_date} arrived in the same change,
     * but a row written between a partial deployment and this migration, or restored from a backup
     * taken then, can carry one without the other. Under the first version — which set the flag only
     * while copying the legacy key — such a row kept {@code agreed: false} beside a real consent
     * date, and {@code progressFor} would have reported the {@code consent} requirement unmet for a
     * clinician who had given it.
     */
    @Test
    void derivesTheFlagFromADateTheNewCodeWroteDirectly() {
        mongoTemplate.insert(
            new Document("account_id", "legacy-dateonly")
                .append("status", ProfileStatus.CREDENTIAL_REVIEW.name())
                .append("authority", "ROLE_NURSE")
                .append("agreed_date", java.util.Date.from(CONSENTED_AT)),
            COLLECTION
        );

        migration.run(null);

        assertThat(raw("legacy-dateonly").get("agreed")).isEqualTo(true);
        assertThat(applicationRepository.findByAccountId("legacy-dateonly").orElseThrow().isAgreed()).isTrue();
    }

    /** Idempotent: a second run matches nothing and changes nothing. */
    @Test
    void isIdempotent() {
        insertLegacy("legacy-twice");

        migration.run(null);
        Document afterFirst = raw("legacy-twice");
        migration.run(null);

        assertThat(raw("legacy-twice")).isEqualTo(afterFirst);
    }

    /**
     * ⛔ <b>A row that already carries the new key is never overwritten from its own legacy one.</b>
     *
     * <p>Reachable in practice rather than theoretical: a database restored from a backup taken
     * mid-run can hold both keys, and the newer field has to win. Here the new values deliberately
     * differ from the legacy ones, so a migration that preferred the old would be visible rather
     * than merely unproven.
     */
    @Test
    void doesNotOverwriteAValueTheNewCodeAlreadyWrote() {
        mongoTemplate.insert(
            new Document("account_id", "legacy-both")
                .append("status", ProfileStatus.CREDENTIAL_REVIEW.name())
                .append("requested_role", "ROLE_NURSE")
                .append("authority", "ROLE_DOCTOR")
                .append("consent_accepted_at", CONSENTED_AT)
                .append("agreed_date", java.util.Date.from(Instant.parse("2026-09-09T09:09:00Z")))
                .append("agreed", true),
            COLLECTION
        );

        migration.run(null);

        Document row = raw("legacy-both");
        assertThat(row.get("authority")).isEqualTo("ROLE_DOCTOR");
        assertThat(row.get("agreed_date")).isEqualTo(java.util.Date.from(Instant.parse("2026-09-09T09:09:00Z")));
        assertThat(row).doesNotContainKeys("requested_role", "consent_accepted_at");
    }

    /** A collection holding nothing legacy is left alone, and the run does not fail. */
    @Test
    void leavesAnAlreadyMigratedCollectionAlone() {
        ProfessionalApplication saved = applicationRepository.save(
            new ProfessionalApplication()
                .accountId("already-migrated")
                .status(ProfileStatus.APPLICATION_STARTED)
                .authority("ROLE_THERAPIST")
                .agreed(true)
                .agreedDate(CONSENTED_AT)
        );

        migration.run(null);

        ProfessionalApplication after = applicationRepository.findById(saved.getId()).orElseThrow();
        assertThat(after.getAuthority()).isEqualTo("ROLE_THERAPIST");
        assertThat(after.getAgreedDate()).isEqualTo(CONSENTED_AT);
        assertThat(after.isAgreed()).isTrue();
    }
}
