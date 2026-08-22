package net.jojoaddison.config;

import net.jojoaddison.domain.PatientWriteReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;
import tech.jhipster.config.JHipsterConstants;

/**
 * Indexes for the {@link PatientWriteReceipt} collection.
 *
 * <p>Created explicitly rather than with {@code @Indexed}, for the same reason
 * {@code DeviceTokenIndexInitializer} does: this project never sets
 * {@code spring.data.mongodb.auto-index-creation}, and Spring Data defaults it to {@code false}, so
 * annotating the domain class would compile, read convincingly, and create nothing.
 *
 * <p>The unique index on {@code client_ref} is not decoration — it is the only thing that closes the
 * race between two concurrent retries of the same queued write. Both can miss the read; only one can
 * win the insert, and the loser re-reads the winner's receipt.
 */
@Component
@Profile("!" + JHipsterConstants.SPRING_PROFILE_TEST)
public class PatientWriteReceiptIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PatientWriteReceiptIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public PatientWriteReceiptIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            mongoTemplate.indexOps(PatientWriteReceipt.class).ensureIndex(new Index().on("client_ref", Sort.Direction.ASC).unique());
            log.debug("patient_write_receipt indexes ensured");
        } catch (Exception e) {
            // Never fatal at boot. A missing index costs idempotency on a concurrent retry, which is
            // worth a loud warning and not worth refusing to start over.
            log.warn("Could not ensure patient_write_receipt indexes: {}", e.getMessage());
        }
    }
}
