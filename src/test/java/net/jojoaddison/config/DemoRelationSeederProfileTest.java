package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * {@link DemoRelationSeeder} registers everywhere except production.
 *
 * <p>Written with item 118 because that item's severity turns entirely on this annotation: a seeder
 * that reverts {@code Profile.accountId} to a login on every start is a quality-stack nuisance under
 * {@code !prod} and an estate-wide identity fault without it. The claim was true and untested, and an
 * annotation is exactly the kind of thing a later edit changes without anything failing.
 *
 * <p>It is the opposite reading from {@link ShiftTypeMigrationProfileTest} and
 * {@link AngelDutyRoleMigrationProfileTest} beside it, deliberately: those two must run on a
 * deployment and not under a build, whereas this one must run under a build — the class javadoc
 * records why {@code @Profile({"dev","test"})} would have been wrong, since this project's tests
 * activate {@code testdev} and {@code testprod} and neither is {@code test}.
 *
 * <p>A plain context rather than a {@code @SpringBootTest}: booting the application under four
 * profile sets to read one bean name would cost four contexts to answer a question about an
 * annotation. The repositories are mocks because the constructor needs them and nothing here calls
 * them.
 */
class DemoRelationSeederProfileTest {

    /** The whole of "production is not affected". */
    @Test
    void doesNotRegisterInProduction() {
        assertThat(registersUnder("prod")).isFalse();
    }

    /** {@code dev} is the quality stack, where the demo clinician is the point. */
    @Test
    void registersOnDevAndOnADevTestDeployment() {
        assertThat(registersUnder("dev")).isTrue();
        assertThat(registersUnder("dev", "test")).isTrue();
    }

    /**
     * The two profiles an integration-test run actually activates, from {@code profile.test} in
     * {@code pom.xml}. Both must register, or the seed would vanish from the test context and take its
     * coverage with it — which is the reason the class is gated on not-production rather than on an
     * allow-list of {@code dev} and {@code test}.
     */
    @Test
    void registersUnderTheProfilesAnIntegrationTestRunActivates() {
        assertThat(registersUnder("testdev")).isTrue();
        assertThat(registersUnder("testprod")).isTrue();
    }

    private boolean registersUnder(String... profiles) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profiles);
            context.registerBean(ProfileRepository.class, () -> mock(ProfileRepository.class));
            context.registerBean(TaskRepository.class, () -> mock(TaskRepository.class));
            context.register(DemoRelationSeeder.class);
            context.refresh();
            return context.getBeanNamesForType(DemoRelationSeeder.class).length == 1;
        }
    }
}
