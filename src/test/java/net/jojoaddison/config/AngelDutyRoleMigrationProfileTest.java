package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * {@link AngelDutyRoleMigration} registers on the profiles it has to run on, and not on the ones a
 * build uses.
 *
 * <p>The sibling of {@link ShiftTypeMigrationProfileTest}, and written for the same reason rather than
 * by symmetry. {@code !test} is the expression these two migrations invite — it reads as "not under
 * test" and excludes nothing from {@code ./mvnw verify}, since the pom activates {@code testdev} or
 * {@code testprod} and never {@code test}, while it does exclude a {@code dev,test} deployment.
 * hc-admin's copy of that mistake meant its migration never ran on a quality stack and twelve rows
 * kept a null column until the stack was reseeded. <b>This repo's quality stack is {@code dev} only,
 * so the first case below is the one that would start failing silently the day anyone adds
 * {@code test} to it for seed fixtures</b> — and a quality stack is where the 104 {@code ANGEL} rows
 * this migration exists for actually are.
 *
 * <p>A plain context rather than a {@code @SpringBootTest}: booting the application under four profile
 * sets to read one bean name would cost four contexts to answer a question about an annotation. The
 * {@link MongoTemplate} is a mock because the constructor needs one and nothing here calls it.
 */
class AngelDutyRoleMigrationProfileTest {

    /** A {@code dev,test} deployment — the shape that broke next door. */
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
     * {@code pom.xml}. Excluding them is belt-and-braces: Spring Boot does not invoke
     * {@link org.springframework.boot.ApplicationRunner} beans under {@code @SpringBootTest} at all, so
     * this migration could not delete another test's fixtures even where it registers.
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
            context.register(AngelDutyRoleMigration.class);
            context.refresh();
            return context.getBeanNamesForType(AngelDutyRoleMigration.class).length == 1;
        }
    }
}
