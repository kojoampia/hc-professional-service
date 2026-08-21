package net.jojoaddison.config;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import net.jojoaddison.broker.DomainEventEnvelope;
import net.jojoaddison.service.PushEventHandler;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

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

    /**
     * The push copy bundles (MOB10), separate from the application's own {@code messageSource}.
     *
     * <p>Separate deliberately, and <b>not</b> {@code @Primary}: {@code i18n/messages} is JHipster's
     * bundle for validation and error-page text, it has no Spanish, and its lifecycle is the
     * generator's. Folding push copy into it would put four hand-maintained user-visible strings
     * under a file the generator may rewrite, and would give the parity test a moving target.
     *
     * <p>{@code fallbackToSystemLocale=false} matters more than it looks. With it left on, a locale
     * with no bundle resolves against <em>the server's</em> locale — so a host running with a German
     * default would answer German to a device that asked for something unknown. English is the
     * documented fallback and {@link net.jojoaddison.service.PushCopyService#normalise} guarantees
     * only the four shipped languages ever reach here anyway.
     */
    @Bean("pushMessageSource")
    public MessageSource pushMessageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("i18n/push");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }
}
