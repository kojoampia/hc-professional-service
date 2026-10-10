package net.jojoaddison.config;

import java.util.function.Consumer;
import net.jojoaddison.broker.AccountDetailsEvent;
import net.jojoaddison.broker.EntityChangeEvent;
import net.jojoaddison.service.MeterConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the two Spring Cloud Function bindings behind the live completion meter — backlog.md
 * row 230, unit A.
 *
 * <p>The bindings live here rather than as {@code @Component}s in {@code broker} because the handler
 * needs the Service layer, and ArchUnit's {@code TechnicalStructureTest} allows Service to be reached
 * only from Web and Config. That is {@code PushNotificationConfiguration}'s reasoning verbatim and it
 * is the right way round: the rule describes a real constraint, and a consumer reaching sideways into
 * services from an undeclared package is exactly what it exists to catch.
 *
 * <p>⛔ <b>A {@code Consumer} bean that is not named in {@code spring.cloud.function.definition} is
 * never bound, and nothing says so.</b> It is a bean, it is injectable, it is covered by unit tests,
 * and no frame ever reaches it — which is how the generated {@code kafkaConsumer} in this repository
 * has been dormant since the scaffold. Both names below must appear in that property in
 * {@code config/application.yml}.
 *
 * <p>⚠ <b>Two tests guard that, and it takes both.</b> {@code MeterBindingTest} reads the shipped
 * YAML and compares it against <em>these annotations by reflection</em> — not against literals, which
 * is how its first version let a renamed bean through green: the YAML still contained the strings the
 * test spelled, so the bean was dormant and 6/6 cases passed. {@code MeterConsumerBindingIT} binds
 * these consumers for real and round-trips a frame, which is the only thing that can prove one
 * arrives — the test classpath's {@code application.yml} <em>replaces</em> the shipped one and names
 * a different function definition, so no ordinary {@code @IntegrationTest} here binds either bean.
 *
 * <p>The destinations and the groups themselves are configured in {@code application.yml}, with the
 * reasoning about why the groups must differ beside them.
 */
@Configuration
public class MeterStreamConfiguration {

    /**
     * The gateway's step-1 verdict, on {@code hc.professional.registration}.
     *
     * <p>Bound to {@link AccountDetailsEvent} — a narrow reader record that tolerates unknown
     * properties — rather than to {@code ProfessionalEvent}, because that topic carries two envelope
     * shapes and a record keyed to one of them makes every frame of the other a conversion failure
     * before any dispatch happens. See that record.
     */
    @Bean("meterAccountEvents")
    public Consumer<AccountDetailsEvent> meterAccountEvents(MeterConsumer consumer) {
        return consumer::onAccountDetails;
    }

    /**
     * Every document this service writes or removes, on {@code professional.event} — the channel
     * {@code EntityChangeAnnouncer} already publishes to, so steps 2, 3 and 4 need no new producer.
     *
     * <p>Typed, unlike its neighbour, and the asymmetry is the channels' rather than a preference:
     * this one carries exactly one {@code type} from exactly one producer.
     */
    @Bean("meterEntityEvents")
    public Consumer<EntityChangeEvent> meterEntityEvents(MeterConsumer consumer) {
        return consumer::onEntityChange;
    }
}
