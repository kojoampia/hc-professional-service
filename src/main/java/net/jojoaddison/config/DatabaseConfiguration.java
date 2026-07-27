package net.jojoaddison.config;

import java.util.ArrayList;
import java.util.List;
import net.jojoaddison.domain.OnboardingEvent;
import net.jojoaddison.domain.ProfessionalApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.mapping.event.ValidatingMongoEventListener;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tech.jhipster.config.JHipsterConstants;
import tech.jhipster.domain.util.JSR310DateConverters.DateToZonedDateTimeConverter;
import tech.jhipster.domain.util.JSR310DateConverters.ZonedDateTimeToDateConverter;

@Configuration
@EnableMongoRepositories("net.jojoaddison.repository")
@Profile("!" + JHipsterConstants.SPRING_PROFILE_CLOUD)
@Import(value = MongoAutoConfiguration.class)
@EnableMongoAuditing(auditorAwareRef = "springSecurityAuditorAware")
public class DatabaseConfiguration {

    /**
     * Auto-index-creation is off, so the onboarding uniqueness guarantees
     * (one Profile / one ProfessionalApplication per account — workflow
     * § Data contracts) are created explicitly at startup. Sparse: legacy
     * documents without an accountId are tolerated.
     */
    @Bean
    public ApplicationRunner onboardingIndexInitializer(MongoTemplate mongoTemplate) {
        return args -> {
            mongoTemplate
                .indexOps(net.jojoaddison.domain.Profile.class)
                .createIndex(new Index("account_id", Sort.Direction.ASC).unique().sparse());
            mongoTemplate
                .indexOps(ProfessionalApplication.class)
                .createIndex(new Index("account_id", Sort.Direction.ASC).unique().sparse());
            mongoTemplate.indexOps(OnboardingEvent.class).createIndex(new Index("application_id", Sort.Direction.ASC));
        };
    }

    @Bean
    public ValidatingMongoEventListener validatingMongoEventListener() {
        return new ValidatingMongoEventListener(validator());
    }

    @Bean
    public LocalValidatorFactoryBean validator() {
        return new LocalValidatorFactoryBean();
    }

    @Bean
    public MongoCustomConversions customConversions() {
        List<Converter<?, ?>> converters = new ArrayList<>();
        converters.add(DateToZonedDateTimeConverter.INSTANCE);
        converters.add(ZonedDateTimeToDateConverter.INSTANCE);
        return new MongoCustomConversions(converters);
    }
}
