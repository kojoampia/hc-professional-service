package net.jojoaddison.config;

import java.util.LinkedHashMap;
import java.util.Map;
import net.jojoaddison.domain.enumeration.ShiftType;
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
import tech.jhipster.config.JHipsterConstants;

/**
 * Migrates retired {@link ShiftType} values on the {@code duty_roster} collection (docs/duty-roster.md
 * § 3, DR1).
 *
 * <p>{@code MORNING} and {@code AFTERNOON} were retired when the enum became four contiguous
 * windows. Each retired value maps to the surviving one it overlaps most:
 *
 * <pre>
 *   MORNING   06–14  ->  DAY      07–15
 *   AFTERNOON 14–22  ->  EVENING  15–23
 * </pre>
 *
 * <p>{@code DAY}, {@code NIGHT} and {@code FLEXIBLE} keep their names and are not touched, though
 * their windows shifted by an hour or two — a NIGHT worked 22:00–06:00 now reads 23:00–07:00. That
 * was accepted when the mapping was agreed: <b>a historic shift may display slightly differently
 * from how it was actually worked.</b>
 *
 * <p>There is no migration framework in this repo — no Liquibase, no Mongock — so this runs as an
 * {@link ApplicationRunner}, in the shape of {@link DeviceTokenIndexInitializer} beside it.
 *
 * <p><b>Idempotent by construction.</b> It matches on the retired values only, so a second run
 * matches nothing and updates nothing; there is no marker document to keep in step and no "has this
 * run" flag that could be wrong. Deployments roll forward and restart freely.
 *
 * <p>Excluded from the test profile so it cannot rewrite an integration test's fixtures underneath
 * it. {@code ShiftTypeMigrationIT} therefore constructs this class directly and calls
 * {@link #run(ApplicationArguments)} itself, which is also the only way to exercise it now that the
 * retired values cannot be expressed through the enum — the test writes the raw strings through
 * {@code MongoTemplate}, exactly as the documents in the database already hold them.
 */
@Component
@Profile("!" + JHipsterConstants.SPRING_PROFILE_TEST)
public class ShiftTypeMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ShiftTypeMigration.class);

    private static final String COLLECTION = "duty_roster";
    private static final String FIELD = "shift";

    /** Retired value -> the surviving window it overlaps most. Insertion order is the log order. */
    private static final Map<String, ShiftType> RETIRED = new LinkedHashMap<>();

    static {
        RETIRED.put("MORNING", ShiftType.DAY);
        RETIRED.put("AFTERNOON", ShiftType.EVENING);
    }

    private final MongoTemplate mongoTemplate;

    public ShiftTypeMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        long total = 0;
        for (Map.Entry<String, ShiftType> entry : RETIRED.entrySet()) {
            Query query = new Query(Criteria.where(FIELD).is(entry.getKey()));
            long matched = mongoTemplate
                .updateMulti(query, new Update().set(FIELD, entry.getValue().name()), COLLECTION)
                .getModifiedCount();
            if (matched > 0) {
                log.info("Migrated {} duty-roster assignment(s) from {} to {}", matched, entry.getKey(), entry.getValue());
                total += matched;
            }
        }
        if (total == 0) {
            log.debug("No retired shift values found on {} — nothing to migrate", COLLECTION);
        }
    }
}
