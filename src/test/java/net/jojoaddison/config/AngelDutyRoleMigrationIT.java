package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.enumeration.DutyRole;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * {@link AngelDutyRoleMigration} — the item 44 deletion of roster rows carrying the retired
 * {@code DutyRole.ANGEL}.
 *
 * <p>This constructs the runner and calls it directly, for {@link ShiftTypeMigrationIT}'s reasons:
 * Spring Boot does not invoke {@link org.springframework.boot.ApplicationRunner} beans under
 * {@code @SpringBootTest} whatever profile they register on, and in any case {@code ANGEL} can no
 * longer be expressed through the enum, so the fixture is written as a raw string through
 * {@link MongoTemplate} — exactly how the documents in a real database hold it.
 */
@IntegrationTest
class AngelDutyRoleMigrationIT {

    private static final String COLLECTION = "duty_roster";

    @Autowired
    private MongoTemplate mongoTemplate;

    private AngelDutyRoleMigration migration;

    @BeforeEach
    void setUp() {
        mongoTemplate.remove(new Query(), COLLECTION);
        migration = new AngelDutyRoleMigration(mongoTemplate);
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.remove(new Query(), COLLECTION);
    }

    private void insert(String duty, String name) {
        mongoTemplate.insert(new Document("duty", duty).append("name", name), COLLECTION);
    }

    private boolean exists(String name) {
        return mongoTemplate.exists(new Query(Criteria.where("name").is(name)), COLLECTION);
    }

    @Test
    void deletesTheRowsCarryingTheRetiredDuty() {
        insert("ANGEL", "a");
        insert("ANGEL", "b");

        migration.run(null);

        assertThat(mongoTemplate.findAll(Document.class, COLLECTION))
            .as("a row naming a value the enum cannot parse breaks every read that touches it")
            .isEmpty();
    }

    /**
     * The half that matters more than the deletion. A migration that over-matches loses real cover
     * arrangements, and the eight surviving disciplines plus {@code OTHER} are what a shift can be.
     */
    @Test
    void leavesEverySurvivingDutyAlone() {
        for (DutyRole surviving : DutyRole.values()) {
            insert(surviving.name(), surviving.name());
        }
        insert("ANGEL", "the-retired-one");

        migration.run(null);

        for (DutyRole surviving : DutyRole.values()) {
            assertThat(exists(surviving.name())).as("%s is a duty somebody still works", surviving).isTrue();
        }
        assertThat(exists("the-retired-one")).isFalse();
        assertThat(mongoTemplate.findAll(Document.class, COLLECTION)).hasSize(DutyRole.values().length);
    }

    /**
     * The property the whole design rests on: there is no marker document and no "already run" flag,
     * so the second run simply matches nothing. A deployment may restart as often as it likes.
     */
    @Test
    void isIdempotent() {
        insert("ANGEL", "a");
        insert("NURSE", "b");

        migration.run(null);
        migration.run(null);
        migration.run(null);

        List<Document> all = mongoTemplate.findAll(Document.class, COLLECTION);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getString("duty")).isEqualTo("NURSE");
    }

    /**
     * Nothing to do is not an error, and this is the case production is actually in — the deployed
     * database held zero {@code duty_roster} documents of any kind when this was written.
     */
    @Test
    void doesNothingOnAnEmptyCollection() {
        migration.run(null);

        assertThat(mongoTemplate.findAll(Document.class, COLLECTION)).isEmpty();
    }
}
