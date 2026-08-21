package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.enumeration.ShiftType;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * {@link ShiftTypeMigration} — the DR1 rewrite of retired {@code ShiftType} values.
 *
 * <p>The runner is {@code @Profile("!test")}, so it does not fire here; this constructs it and calls
 * it directly. That is deliberate rather than a workaround — a runner that rewrote rows while other
 * integration tests were setting up would make their failures inexplicable — and it is the only way
 * to exercise the migration at all, since {@code MORNING} and {@code AFTERNOON} can no longer be
 * expressed through the enum. The fixtures are written as raw strings through {@link MongoTemplate},
 * which is exactly how the documents already in the database hold them.
 */
@IntegrationTest
class ShiftTypeMigrationIT {

    private static final String COLLECTION = "duty_roster";

    @Autowired
    private MongoTemplate mongoTemplate;

    private ShiftTypeMigration migration;

    @BeforeEach
    void setUp() {
        mongoTemplate.remove(new Query(), COLLECTION);
        migration = new ShiftTypeMigration(mongoTemplate);
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.remove(new Query(), COLLECTION);
    }

    private void insert(String shift, String name) {
        mongoTemplate.insert(new Document("shift", shift).append("name", name), COLLECTION);
    }

    private String shiftOf(String name) {
        Document found = mongoTemplate.findOne(new Query(Criteria.where("name").is(name)), Document.class, COLLECTION);
        return found == null ? null : found.getString("shift");
    }

    @Test
    void mapsRetiredValuesToTheWindowTheyOverlapMost() {
        insert("MORNING", "a");
        insert("AFTERNOON", "b");

        migration.run(null);

        assertThat(shiftOf("a")).isEqualTo(ShiftType.DAY.name());
        assertThat(shiftOf("b")).isEqualTo(ShiftType.EVENING.name());
    }

    @Test
    void leavesSurvivingValuesAlone() {
        for (ShiftType surviving : ShiftType.values()) {
            insert(surviving.name(), surviving.name());
        }

        migration.run(null);

        for (ShiftType surviving : ShiftType.values()) {
            assertThat(shiftOf(surviving.name())).isEqualTo(surviving.name());
        }
    }

    /**
     * The property the whole design rests on: there is no marker document and no "already run" flag,
     * so the second run simply matches nothing. A deployment may restart as often as it likes.
     */
    @Test
    void isIdempotent() {
        insert("MORNING", "a");

        migration.run(null);
        migration.run(null);
        migration.run(null);

        assertThat(shiftOf("a")).isEqualTo(ShiftType.DAY.name());
        List<Document> all = mongoTemplate.findAll(Document.class, COLLECTION);
        assertThat(all).hasSize(1);
    }

    /** Nothing to do is not an error — a fresh database has no retired values in it. */
    @Test
    void doesNothingOnAnEmptyCollection() {
        migration.run(null);

        assertThat(mongoTemplate.findAll(Document.class, COLLECTION)).isEmpty();
    }
}
