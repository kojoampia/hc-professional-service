package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.Sex;
import net.jojoaddison.repository.ProfileRepository;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * {@link ProfileEnumValueMigration} — stored free text in {@code sex} and {@code card_type}
 * reconciled with the enumerations those fields became (F9).
 *
 * <p>Constructed and called directly, for the reason {@code ShiftTypeMigrationIT} records: Spring
 * Boot does not invoke {@link org.springframework.boot.ApplicationRunner} beans under
 * {@code @SpringBootTest}.
 *
 * <p><b>The fixtures are raw {@link Document}s and necessarily so</b> — a value outside the
 * enumeration cannot be written through the entity any more, which is the whole state this migration
 * exists to leave behind. A migration cannot be set up in the vocabulary it replaces.
 *
 * <p>⚠ <b>Every case ends by reading the row back THROUGH THE ENTITY.</b> Asserting the BSON only
 * would pass against a shape Spring Data cannot map, which is the failure with real consequences:
 * an unreconciled row throws on the first read of it rather than at migration time, so the defect
 * surfaces as a 500 in whatever code touches the profile next.
 */
@IntegrationTest
class ProfileEnumValueMigrationIT {

    private static final String COLLECTION = "profile";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ProfileRepository profileRepository;

    private ProfileEnumValueMigration migration;

    @BeforeEach
    void setUp() {
        mongoTemplate.remove(new Query(), COLLECTION);
        mongoTemplate.remove(new Query(), ProfileEnumValueMigration.QUARANTINE_COLLECTION);
        migration = new ProfileEnumValueMigration(mongoTemplate);
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.remove(new Query(), COLLECTION);
        mongoTemplate.remove(new Query(), ProfileEnumValueMigration.QUARANTINE_COLLECTION);
    }

    private void store(String id, String sex, String cardType) {
        Document row = new Document("_id", id).append("account_id", id).append("first_name", "Ama");
        if (sex != null) {
            row.append("sex", sex);
        }
        if (cardType != null) {
            row.append("card_type", cardType);
        }
        mongoTemplate.insert(row, COLLECTION);
    }

    private List<Document> quarantineRecords() {
        return mongoTemplate.findAll(Document.class, ProfileEnumValueMigration.QUARANTINE_COLLECTION);
    }

    // -------------------------------------------------------------------------------------------------
    // Normalisation: a member name differing only by case or space
    // -------------------------------------------------------------------------------------------------

    /**
     * ⭐ <b>The row the quality database actually held</b> — exactly one, {@code sex: "female"}, and
     * no unmappable value at all. Quarantining it would discard a value a clinician entered over
     * nothing but capitalisation; normalising it without a record would be a silent rewrite. It does
     * both: rewrite, and record.
     */
    @Test
    void aMemberNameInTheWrongCaseIsNormalisedAndRecorded() {
        store("p1", "female", null);

        migration.run(null);

        Profile after = profileRepository.findById("p1").orElseThrow();
        assertThat(after.getSex()).isEqualTo(Sex.FEMALE);

        assertThat(quarantineRecords()).hasSize(1);
        Document record = quarantineRecords().get(0);
        assertThat(record.getString("field")).isEqualTo("sex");
        assertThat(record.getString("oldValue")).isEqualTo("female");
        assertThat(record.getString("newValue")).isEqualTo("FEMALE");
        assertThat(record.getString("action")).isEqualTo("normalised");
        assertThat(record.get("documentId")).isEqualTo("p1");
    }

    /** Surrounding space is the same case, and is the shape a pasted value arrives in. */
    @Test
    void aMemberNameWithSurroundingSpaceIsNormalised() {
        store("p1", "  MALE ", null);

        migration.run(null);

        assertThat(profileRepository.findById("p1").orElseThrow().getSex()).isEqualTo(Sex.MALE);
    }

    /** {@code card_type} goes through the same path against the nine {@link DocumentType} members. */
    @Test
    void aCardTypeInTheWrongCaseIsNormalised() {
        store("p1", null, "ghanacard");

        migration.run(null);

        assertThat(profileRepository.findById("p1").orElseThrow().getCardType()).isEqualTo(DocumentType.GHANACARD);
    }

    // -------------------------------------------------------------------------------------------------
    // Quarantine: a value that is no member at all
    // -------------------------------------------------------------------------------------------------

    /**
     * ⛔ <b>Quarantined, not mapped and not silently nulled.</b> The house pattern is
     * {@code orphaned_account_row}: the key is cleared so the row becomes readable, and the old value
     * is recorded for somebody to reconcile. Inventing a mapping would manufacture a fact nobody
     * stated; nulling it with no record would discard something a clinician typed with nothing
     * anywhere to recover it from.
     */
    @Test
    void aValueOutsideTheEnumerationIsQuarantinedWithItsOldValueRecorded() {
        store("p1", "banana", "loyalty card");

        migration.run(null);

        Profile after = profileRepository.findById("p1").orElseThrow();
        assertThat(after.getSex()).isNull();
        assertThat(after.getCardType()).isNull();
        assertThat(after.getFirstName()).as("the rest of the row is untouched").isEqualTo("Ama");

        assertThat(quarantineRecords()).hasSize(2);
        assertThat(quarantineRecords().stream().map(record -> record.getString("oldValue"))).containsExactlyInAnyOrder(
            "banana",
            "loyalty card"
        );
        assertThat(quarantineRecords().stream().map(record -> record.getString("action"))).containsOnly("quarantined");
        assertThat(quarantineRecords().stream().map(record -> record.getString("newValue"))).containsOnlyNulls();
    }

    /** A blank value is no member either, so it is quarantined rather than normalised. */
    @Test
    void aBlankValueIsQuarantined() {
        store("p1", "   ", null);

        migration.run(null);

        assertThat(profileRepository.findById("p1").orElseThrow().getSex()).isNull();
        assertThat(quarantineRecords()).hasSize(1);
        assertThat(quarantineRecords().get(0).getString("action")).isEqualTo("quarantined");
    }

    // -------------------------------------------------------------------------------------------------
    // What it must leave alone
    // -------------------------------------------------------------------------------------------------

    /** A value already a member is not touched, and writes no record. */
    @Test
    void aValueThatIsAlreadyAMemberIsLeftAlone() {
        store("p1", "FEMALE", "GHANACARD");

        migration.run(null);

        Profile after = profileRepository.findById("p1").orElseThrow();
        assertThat(after.getSex()).isEqualTo(Sex.FEMALE);
        assertThat(after.getCardType()).isEqualTo(DocumentType.GHANACARD);
        assertThat(quarantineRecords()).isEmpty();
    }

    /** A row with neither key is not given one. */
    @Test
    void aRowWithNeitherFieldIsNotTouched() {
        store("p1", null, null);

        migration.run(null);

        assertThat(mongoTemplate.findOne(new Query(Criteria.where("_id").is("p1")), Document.class, COLLECTION))
            .as("no key invented for a row that carried none")
            .doesNotContainKey("sex")
            .doesNotContainKey("card_type");
        assertThat(quarantineRecords()).isEmpty();
    }

    // -------------------------------------------------------------------------------------------------
    // Idempotence, by construction rather than by a flag
    // -------------------------------------------------------------------------------------------------

    /**
     * A second run matches nothing: it reads only values that are not already member names, and the
     * first run leaves every row holding either a member or no key at all.
     */
    @Test
    void asecondRunChangesNothingAndRecordsNothingFurther() {
        store("p1", "female", null);
        store("p2", "banana", null);

        migration.run(null);
        int recordsAfterFirstRun = quarantineRecords().size();

        migration.run(null);

        assertThat(quarantineRecords()).hasSize(recordsAfterFirstRun);
        assertThat(profileRepository.findById("p1").orElseThrow().getSex()).isEqualTo(Sex.FEMALE);
        assertThat(profileRepository.findById("p2").orElseThrow().getSex()).isNull();
    }

    /** Several rows in one pass, each getting its own answer. */
    @Test
    void eachRowIsReconciledOnItsOwnValue() {
        store("normalise", "female", null);
        store("quarantine", "banana", null);
        store("untouched", "MALE", null);

        migration.run(null);

        assertThat(profileRepository.findById("normalise").orElseThrow().getSex()).isEqualTo(Sex.FEMALE);
        assertThat(profileRepository.findById("quarantine").orElseThrow().getSex()).isNull();
        assertThat(profileRepository.findById("untouched").orElseThrow().getSex()).isEqualTo(Sex.MALE);
        assertThat(quarantineRecords()).hasSize(2);
    }
}
