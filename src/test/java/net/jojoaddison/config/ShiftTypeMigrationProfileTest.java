package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * {@link ShiftTypeMigration} registers on the profiles it has to run on, and not on the ones a build
 * uses.
 *
 * <p><b>Nothing was broken in this repo when this was written — it is the sibling's defect, pinned
 * before it can be inherited.</b> The annotation read {@code !test} until 2026-09-04, an expression
 * that excludes nothing from {@code ./mvnw verify} (the pom activates {@code testdev} or
 * {@code testprod} for an integration-test run, never {@code test}) and does exclude a
 * {@code dev,test} deployment. hc-admin's quality stack runs {@code dev,test}: its migration never
 * ran there, twelve {@code wage_rate} rows kept a null {@code shift_type}, and its earnings screens
 * answered 500 until the stack was reseeded. <b>This repo's quality stack is {@code dev} only, so the
 * same annotation was harmless here by coincidence</b> — the first case below is the one that would
 * have started failing silently the day anyone added {@code test} to it for seed fixtures.
 *
 * <p>It is a plain context rather than a {@code @SpringBootTest}, because booting the application
 * under four profile sets to read one bean name would cost four contexts to answer a question about
 * an annotation. The {@link MongoTemplate} is a mock for the same reason — the constructor needs one
 * and nothing here calls it.
 */
class ShiftTypeMigrationProfileTest {

    /** A {@code dev,test} deployment — what hc-admin's quality stack runs, and what broke there. */
    @Test
    void registersOnADevTestDeployment() {
        assertThat(registersUnder("dev", "test")).isTrue();
    }

    @Test
    void registersOnDevAndOnProd() {
        assertThat(registersUnder("dev")).isTrue();
        assertThat(registersUnder("prod")).isTrue();
    }

    /**
     * The two profiles an integration-test run actually activates, from {@code profile.test} in
     * {@code pom.xml}.
     *
     * <p>Excluding them is belt-and-braces rather than the reason the annotation exists: Spring Boot
     * does not invoke {@link org.springframework.boot.ApplicationRunner} beans under
     * {@code @SpringBootTest} at all, so the migration could not have rewritten an IT's fixtures even
     * when it registered in every one of their contexts — which, under {@code !test}, it did.
     */
    @Test
    void doesNotRegisterUnderTheProfilesAnIntegrationTestRunActivates() {
        assertThat(registersUnder("testdev")).isFalse();
        assertThat(registersUnder("testprod")).isFalse();
    }

    private boolean registersUnder(String... profiles) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profiles);
            context.registerBean(MongoTemplate.class, () -> mock(MongoTemplate.class));
            context.register(ShiftTypeMigration.class);
            context.refresh();
            return context.getBeanNamesForType(ShiftTypeMigration.class).length == 1;
        }
    }
}
