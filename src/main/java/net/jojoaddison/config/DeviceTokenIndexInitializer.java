package net.jojoaddison.config;

import net.jojoaddison.domain.DeviceToken;
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
 * Indexes for the {@link DeviceToken} collection.
 *
 * <p>Created explicitly rather than with {@code @Indexed}, for the same reason the gateway's
 * refresh-token indexes are: this project never sets
 * {@code spring.data.mongodb.auto-index-creation}, and Spring Data defaults it to {@code false}.
 * Annotating the domain class would compile, read convincingly, and create nothing.
 *
 * <p>The unique index on {@code token} is what makes registration an upsert rather than a source of
 * duplicates — and it is what would surface, loudly, if the reassignment path in
 * {@code DeviceTokenResource} were ever removed.
 */
@Component
@Profile("!" + JHipsterConstants.SPRING_PROFILE_TEST)
public class DeviceTokenIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public DeviceTokenIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            var indexOps = mongoTemplate.indexOps(DeviceToken.class);
            indexOps.ensureIndex(new Index().on("token", Sort.Direction.ASC).unique());
            indexOps.ensureIndex(new Index().on("account_id", Sort.Direction.ASC));
            log.debug("device_token indexes ensured");
        } catch (Exception e) {
            // Never let index creation stop the application from starting.
            log.error("Could not create device_token indexes", e);
        }
    }
}
