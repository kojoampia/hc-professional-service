package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jojoaddison.IntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * {@link AddressFieldRemovalMigration} — {@code area_code} and {@code state} dropped from all three
 * places an {@code Address} is stored (F10).
 *
 * <p>Constructed and called directly: Spring Boot does not invoke
 * {@link org.springframework.boot.ApplicationRunner} beans under {@code @SpringBootTest}.
 *
 * <p><b>Raw {@link Document}s throughout, on both sides of every case</b>, and here that is not
 * merely a setup convenience — the <em>assertions</em> have to be raw too. The entity no longer
 * declares either field, so reading a row back through {@code Profile} would report the keys absent
 * whether the migration removed them or Spring Data merely ignored them. Only {@code doesNotContainKey}
 * on the stored document can tell "removed" from "not mapped", and that distinction is the whole
 * subject of this class.
 */
@IntegrationTest
class AddressFieldRemovalMigrationIT {

    private static final String PROFILE = "profile";
    private static final String ADDRESS = "address";

    @Autowired
    private MongoTemplate mongoTemplate;

    private AddressFieldRemovalMigration migration;

    @BeforeEach
    void setUp() {
        mongoTemplate.remove(new Query(), PROFILE);
        mongoTemplate.remove(new Query(), ADDRESS);
        migration = new AddressFieldRemovalMigration(mongoTemplate);
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.remove(new Query(), PROFILE);
        mongoTemplate.remove(new Query(), ADDRESS);
    }

    /** An address carrying both retired keys and the eight that survive. */
    private static Document legacyAddress() {
        return new Document("street_address", "12 Oxford St")
            .append("area_code", "GA-123")
            .append("city", "Accra")
            .append("state", "Greater Accra")
            .append("region", "Greater Accra")
            .append("country", "Ghana");
    }

    private Document reload(String collection, String id) {
        return mongoTemplate.findOne(new Query(Criteria.where("_id").is(id)), Document.class, collection);
    }

    @SuppressWarnings("unchecked")
    private static Document nested(Document row, String key) {
        return (Document) row.get(key);
    }

    // -------------------------------------------------------------------------------------------------
    // The three places
    // -------------------------------------------------------------------------------------------------

    @Test
    void theKeysGoFromTheAddressCollection() {
        mongoTemplate.insert(legacyAddress().append("_id", "a1"), ADDRESS);

        migration.run(null);

        Document after = reload(ADDRESS, "a1");
        assertThat(after).doesNotContainKey("area_code").doesNotContainKey("state");
        assertThat(after).containsEntry("street_address", "12 Oxford St").containsEntry("region", "Greater Accra");
    }

    @Test
    void theKeysGoFromTheEmbeddedProfileAddress() {
        mongoTemplate.insert(new Document("_id", "p1").append("account_id", "p1").append("address", legacyAddress()), PROFILE);

        migration.run(null);

        Document address = nested(reload(PROFILE, "p1"), "address");
        assertThat(address).doesNotContainKey("area_code").doesNotContainKey("state");
        assertThat(address).containsEntry("city", "Accra");
    }

    /**
     * ⚠ <b>The case that fails silently if the update path is written as a plain dotted one.</b>
     *
     * <p>A <em>query</em> traverses an array implicitly, so {@code contacts.address.area_code}
     * matches; an <em>update</em> does not, so {@code contacts.address.area_code} unsets nothing and
     * reports a modified document while doing it. The migration uses {@code contacts.$[].address.}
     * on the update side only, and this is the assertion that notices if the two prefixes are ever
     * collapsed into one.
     */
    @Test
    void theKeysGoFromEveryContactsEmbeddedAddress() {
        mongoTemplate.insert(
            new Document("_id", "p1")
                .append("account_id", "p1")
                .append(
                    "contacts",
                    List.of(
                        new Document("name", "Efua").append("address", legacyAddress()),
                        new Document("name", "Kojo").append("address", legacyAddress())
                    )
                ),
            PROFILE
        );

        migration.run(null);

        @SuppressWarnings("unchecked")
        List<Document> contacts = (List<Document>) reload(PROFILE, "p1").get("contacts");
        assertThat(contacts).hasSize(2);
        for (Document contact : contacts) {
            assertThat(nested(contact, "address"))
                .as("every contact's address, not only the first — the migration uses $[]")
                .doesNotContainKey("area_code")
                .doesNotContainKey("state");
            assertThat(nested(contact, "address")).containsEntry("street_address", "12 Oxford St");
        }
    }

    /** All three in one run, since that is how it is actually invoked. */
    @Test
    void allThreeLocationsAreSweptInOneRun() {
        mongoTemplate.insert(legacyAddress().append("_id", "a1"), ADDRESS);
        mongoTemplate.insert(
            new Document("_id", "p1")
                .append("account_id", "p1")
                .append("address", legacyAddress())
                .append("contacts", List.of(new Document("name", "Efua").append("address", legacyAddress()))),
            PROFILE
        );

        migration.run(null);

        assertThat(reload(ADDRESS, "a1")).doesNotContainKey("area_code");
        Document profile = reload(PROFILE, "p1");
        assertThat(nested(profile, "address")).doesNotContainKey("state");
        @SuppressWarnings("unchecked")
        List<Document> contacts = (List<Document>) profile.get("contacts");
        assertThat(nested(contacts.get(0), "address")).doesNotContainKey("area_code").doesNotContainKey("state");
    }

    // -------------------------------------------------------------------------------------------------
    // One key without the other, and idempotence
    // -------------------------------------------------------------------------------------------------

    /**
     * A row carrying only one of the two is still swept. The query is an {@code $or} rather than an
     * {@code $and} for exactly this: a row with {@code state} and no {@code area_code} is the
     * ordinary case, since nothing ever wrote them as a pair.
     */
    @Test
    void aRowCarryingOnlyOneOfTheTwoKeysIsStillSwept() {
        Document onlyState = new Document("street_address", "3 High St").append("state", "Ashanti");
        mongoTemplate.insert(new Document("_id", "a1").append("street_address", "x").append("state", "Ashanti"), ADDRESS);
        mongoTemplate.insert(new Document("_id", "p1").append("account_id", "p1").append("address", onlyState), PROFILE);

        migration.run(null);

        assertThat(reload(ADDRESS, "a1")).doesNotContainKey("state");
        assertThat(nested(reload(PROFILE, "p1"), "address")).doesNotContainKey("state");
    }

    /** A second run matches nothing, because the first leaves no document carrying either key. */
    @Test
    void aSecondRunChangesNothing() {
        mongoTemplate.insert(new Document("_id", "p1").append("account_id", "p1").append("address", legacyAddress()), PROFILE);

        migration.run(null);
        Document afterFirst = reload(PROFILE, "p1");

        migration.run(null);

        assertThat(reload(PROFILE, "p1")).isEqualTo(afterFirst);
    }

    /** An empty database — the state of the quality stack when this was written — is fine. */
    @Test
    void anEmptyDatabaseIsFine() {
        migration.run(null);

        assertThat(mongoTemplate.findAll(Document.class, PROFILE)).isEmpty();
        assertThat(mongoTemplate.findAll(Document.class, ADDRESS)).isEmpty();
    }

    /** A row that never carried either key keeps every field it does have. */
    @Test
    void aRowThatNeverCarriedTheKeysIsUntouched() {
        mongoTemplate.insert(
            new Document("_id", "p1")
                .append("account_id", "p1")
                .append("address", new Document("street_address", "12 Oxford St").append("city", "Accra")),
            PROFILE
        );

        migration.run(null);

        assertThat(nested(reload(PROFILE, "p1"), "address")).containsEntry("street_address", "12 Oxford St").containsEntry("city", "Accra");
    }
}
