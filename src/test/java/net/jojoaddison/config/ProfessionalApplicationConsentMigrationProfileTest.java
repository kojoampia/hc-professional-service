package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * {@link ProfessionalApplicationConsentMigration} registers on the profiles it has to run on, and
 * not on the ones a build uses.
 *
 * <p>The same cases as {@link ShiftTypeMigrationProfileTest}, and for the same reason recorded there
 * at length: the expression is {@code !testdev & !testprod} rather than {@code !test} because the
 * Spring profile {@code test} is never active under {@code ./mvnw verify} — the pom activates
 * {@code testdev} or {@code testprod} — so {@code !test} excludes nothing from a build while it does
 * exclude a {@code dev,test} deployment, which cost hc-admin an outage. <b>This repo's quality stack
 * is {@code dev} only, so the wrong expression would be harmless here by coincidence</b>, which is
 * exactly why it is pinned rather than trusted.
 *
 * <p>Written as its own class rather than as cases added to a sibling's, because they are claims
 * about different beans and a class that asserted several would stay green while one of them lost
 * its annotation.
 *
 * <p>A plain context rather than a {@code @SpringBootTest}, and a mock {@link MongoTemplate}: the
 * constructor needs one and nothing here calls it.
 */
class ProfessionalApplicationConsentMigrationProfileTest {

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
     * <p>It matters more here than belt-and-braces: this migration rewrites
     * {@code professional_application}, which a dozen ITs in this repository seed, so a runner that
     * fired under a test context would be rewriting another test's fixtures.
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
            context.register(ProfessionalApplicationConsentMigration.class);
            context.refresh();
            return context.getBeanNamesForType(ProfessionalApplicationConsentMigration.class).length == 1;
        }
    }
}
