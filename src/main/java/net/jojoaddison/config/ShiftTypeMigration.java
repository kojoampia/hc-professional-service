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
 * <p><b>{@code OFF} (2026-09-04) needed nothing added here, and that is worth stating rather than
 * leaving to be inferred.</b> The superset change <em>added</em> a value rather than retiring one, so
 * no stored document holds a string the enum can no longer parse and there is nothing to rewrite —
 * this run stays a no-op for it. The class and this note are kept because "the migration was empty"
 * and "the migration was forgotten" look identical from a green build, and because a pre-launch
 * database that has not been started since DR1 is still out there to be found. hc-admin's half of the
 * change grew its own {@code ShiftTypeMigration} in the same shape, and for the same reason.
 *
 * <p>There is no migration framework in this repo — no Liquibase, no Mongock — so this runs as an
 * {@link ApplicationRunner}, in the shape of {@link DeviceTokenIndexInitializer} beside it.
 *
 * <p><b>Idempotent by construction.</b> It matches on the retired values only, so a second run
 * matches nothing and updates nothing; there is no marker document to keep in step and no "has this
 * run" flag that could be wrong. Deployments roll forward and restart freely.
 *
 * <p><b>Excluded from {@code testdev} and {@code testprod}</b> — the two profiles {@code pom.xml}
 * activates for an integration-test run, via {@code -Dspring.profiles.active=${profile.test}} on
 * surefire/failsafe. It said {@code !test} until 2026-09-04 and was changed here <b>while nothing was
 * broken in this repo</b>, because the same expression had already caused an outage next door: the
 * Spring profile {@code test} is never active under {@code ./mvnw verify}, so {@code !test} excluded
 * nothing from a build, while it does exclude a {@code dev,test} deployment. hc-admin's quality stack
 * runs {@code dev,test} and its migration therefore never ran there, leaving twelve {@code wage_rate}
 * rows null and every earnings screen answering 500. <b>This repo's quality stack is {@code dev} only,
 * so it works today by coincidence</b> — the day anyone adds {@code test} to get seed fixtures, this
 * migration stops running with no log line and no failing test.
 *
 * <p><b>An integration test was never protected by this annotation, whichever expression it held.</b>
 * Spring Boot does not invoke {@link ApplicationRunner} beans under {@code @SpringBootTest} — it is
 * {@code SpringApplication.run} that calls them — so no IT's fixtures were ever reachable from here.
 * The expression above is the house idiom ({@code AsyncConfiguration} beside it) and starts genuinely
 * excluding IT contexts, which {@code !test} never did.
 *
 * <p>{@code ShiftTypeMigrationIT} therefore constructs this class directly and calls
 * {@link #run(ApplicationArguments)} itself, which is also the only way to exercise it now that the
 * retired values cannot be expressed through the enum — the test writes the raw strings through
 * {@code MongoTemplate}, exactly as the documents in the database already hold them.
 * {@code ShiftTypeMigrationProfileTest} pins the registration itself.
 */
@Component
@Profile("!testdev & !testprod")
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
