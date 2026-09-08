package net.jojoaddison.config;

import net.jojoaddison.domain.enumeration.DutyRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Deletes {@code duty_roster} documents left behind by the retired {@link DutyRole} value
 * {@code ANGEL} (docs/backlog.md item 44).
 *
 * <p>A care angel supports one named patient. hc-patient owns the concept, the {@code ROLE_ANGEL}
 * authority and the whole surface for it — an {@code ACTIVE CareDelegation} re-read per request — and
 * on 2026-09-08 this subsystem stopped naming it at all. {@code DutyRole.ANGEL} went with the rest, so
 * a stored row reading {@code "duty": "ANGEL"} names a value the enum can no longer parse and every
 * read that touches it now fails to deserialize.
 *
 * <p><b>Deleted rather than remapped, and that is the architect's explicit choice.</b>
 * {@link ShiftTypeMigration} beside this one rewrites its retired values to the surviving window they
 * overlap most, because a shift that was actually worked is a fact and the question is only what to
 * call it. This is the opposite case: there is no surviving duty an angel's shift should become. An
 * angel covers no duty anybody rosters — the eight disciplines are what a shift can be — so mapping
 * these rows to {@code CARER} or {@code OTHER} would invent a cover arrangement that never existed and
 * put it in front of whoever plans the week. Losing the row loses nothing true.
 *
 * <p><b>Nothing in production is touched by this, and that is measured rather than assumed.</b> The
 * deployed database held <b>0</b> {@code duty_roster} documents in total on 2026-09-08 and <b>0</b>
 * accounts holding {@code ROLE_ANGEL}. The 104 rows this deletes all live on the quality stack, which
 * seeds them from {@code quality/seed-data.py} and regenerates them on {@code ./startup.sh --clean}.
 * The class exists for the database nobody has looked at — a long-lived pre-launch instance, or the
 * next one restored from an old dump — where the alternative to deleting the row is a roster screen
 * that 500s on a value nothing can read.
 *
 * <p>There is no migration framework in this repo — no Liquibase, no Mongock — so this runs as an
 * {@link ApplicationRunner}, in the shape of {@link ShiftTypeMigration} and
 * {@link DeviceTokenIndexInitializer} beside it.
 *
 * <p><b>Idempotent by construction.</b> It matches on the retired value only, so a second run matches
 * nothing and deletes nothing; there is no marker document to keep in step and no "has this run" flag
 * that could be wrong. Deployments roll forward and restart freely, and a database with none of these
 * rows — which is every database that matters today — is a supported and silent case.
 *
 * <p><b>Excluded from {@code testdev} and {@code testprod}</b>, the two profiles {@code pom.xml}
 * activates for an integration-test run. That is the house idiom and the reasoning is
 * {@link ShiftTypeMigration}'s: {@code !test} would exclude nothing from a build while excluding a
 * {@code dev,test} deployment, which is how hc-admin's equivalent silently stopped running. Note that
 * an {@link ApplicationRunner} is not invoked under {@code @SpringBootTest} whatever profile it
 * registers on, so {@code AngelDutyRoleMigrationIT} constructs this class and calls
 * {@link #run(ApplicationArguments)} itself — which is also the only way to exercise it, since
 * {@code ANGEL} can no longer be expressed through the enum and the fixture has to be written as a raw
 * string through {@link MongoTemplate}, exactly as the documents in the database hold it.
 */
@Component
@Profile("!testdev & !testprod")
public class AngelDutyRoleMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AngelDutyRoleMigration.class);

    private static final String COLLECTION = "duty_roster";
    private static final String FIELD = "duty";

    /** The retired {@link DutyRole} value, as it is spelled in the stored documents. */
    private static final String RETIRED = "ANGEL";

    private final MongoTemplate mongoTemplate;

    public AngelDutyRoleMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Query query = new Query(Criteria.where(FIELD).is(RETIRED));
        long deleted = mongoTemplate.remove(query, COLLECTION).getDeletedCount();
        if (deleted > 0) {
            log.info("Deleted {} duty-roster assignment(s) carrying the retired duty {} — see docs/backlog.md item 44", deleted, RETIRED);
        } else {
            log.debug("No {} duty-roster assignments found on {} — nothing to delete", RETIRED, COLLECTION);
        }
    }
}
