package net.jojoaddison.config;

import java.util.function.Consumer;
import net.jojoaddison.broker.DomainEventEnvelope;
import net.jojoaddison.service.PushEventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code pushEvents} Spring Cloud Function binding (MOB9).
 *
 * <p>The binding lives here rather than as a {@code @Component} in {@code broker} because the
 * handler needs the Service layer, and ArchUnit's {@code TechnicalStructureTest} allows Service to
 * be reached only from Web and Config. Declaring the function as a Config bean keeps the dependency
 * direction legal without touching the rule — which is the right way round: the rule is describing
 * a real constraint, and a consumer that reaches sideways into services from an undeclared package
 * is exactly what it exists to catch.
 *
 * <p>The binding itself, including the consumer group that must differ from
 * {@code messageEvents-in-0}, is configured in {@code application.yml}.
 */
@Configuration
public class PushNotificationConfiguration {

    @Bean("pushEvents")
    public Consumer<DomainEventEnvelope> pushEvents(PushEventHandler handler) {
        return handler::handle;
    }
}
