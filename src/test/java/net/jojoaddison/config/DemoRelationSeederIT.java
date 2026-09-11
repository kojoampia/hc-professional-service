package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.Task;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.repository.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * {@link DemoRelationSeeder} — backlog.md item 118: the seeder must survive a restart of an
 * already-migrated database.
 *
 * <p><b>Every test here runs the seeder at least twice, and that is the whole point.</b> The defect
 * this class exists for is invisible on a first boot and invisible on an unmigrated database — the
 * guard asked {@code findByAccountId("doctor")} and the write collided on
 * {@code _id: professional-doctor}, which named the same row until item 50 moved {@code accountId}
 * onto the gateway's {@code User.id}. A single-run test passed throughout, which is why the seeder
 * spent a day reverting that migration on every start of the quality stack while answering
 * {@code 200} to everything.
 *
 * <p>The migration is simulated with a {@link MongoTemplate} write to {@code account_id} rather than
 * through the entity, because that is exactly how {@code AccountIdMigrationService} moves it — and
 * because a fixture built from the setter would be asserting the seeder against itself.
 *
 * <p>Constructing the runner and calling it directly, like {@link ShiftTypeMigrationIT}: Spring Boot
 * does not invoke {@link org.springframework.boot.ApplicationRunner} beans under
 * {@code @SpringBootTest} whatever profile they register on, so a second start has to be spelled out.
 * {@link DemoRelationSeederProfileTest} covers where it registers.
 */
@IntegrationTest
class DemoRelationSeederIT {

    private static final String DEMO_PROFESSIONAL_ID = "professional-doctor";
    private static final String DEMO_ACCOUNT = "doctor";

    /** What the migration would leave behind: a gateway {@code User.id}, not a login. */
    private static final String MIGRATED_ACCOUNT_ID = "308c5b23-caaf-48a4-8638-942c927a4ae4";

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    private DemoRelationSeeder seeder;

    @BeforeEach
    void setUp() {
        removeDemoRows();
        seeder = new DemoRelationSeeder(profileRepository, taskRepository);
    }

    @AfterEach
    void tearDown() {
        removeDemoRows();
    }

    /** Scoped to this seeder's own rows — the two collections belong to the rest of the suite too. */
    private void removeDemoRows() {
        profileRepository.deleteById(DEMO_PROFESSIONAL_ID);
        taskRepository.deleteAll(taskRepository.findByAttendantId(DEMO_PROFESSIONAL_ID));
    }

    /** Writes {@code account_id} the way {@code AccountIdMigrationService} does. */
    private void migrateDemoAccountId() {
        var result = mongoTemplate.updateFirst(
            new Query(Criteria.where("_id").is(DEMO_PROFESSIONAL_ID)),
            new Update().set("account_id", MIGRATED_ACCOUNT_ID),
            "profile"
        );
        assertThat(result.getMatchedCount()).as("the fixture must actually have migrated a row").isEqualTo(1);
    }

    private String storedAccountId() {
        return profileRepository.findById(DEMO_PROFESSIONAL_ID).map(Profile::getAccountId).orElse(null);
    }

    private List<Task> demoTasks() {
        return taskRepository.findByAttendantId(DEMO_PROFESSIONAL_ID);
    }

    /**
     * <b>The central test.</b> Seed, migrate the key the way the estate does, start again.
     *
     * <p>Both assertions are made because they are independent symptoms of one guard and a fix could
     * close either alone: the profile save reverted {@code account_id}, and the task loop it guarded
     * appended a second set of links on the same branch.
     */
    @Test
    void aRestartOverAMigratedRowChangesNeitherTheAccountIdNorTheLinks() {
        seeder.run(null);
        migrateDemoAccountId();

        seeder.run(null);

        assertThat(storedAccountId())
            .as("the seeder must not put the login back over a migrated User.id — backlog.md items 50 and 118")
            .isEqualTo(MIGRATED_ACCOUNT_ID);
        assertThat(demoTasks()).as("a restart must not append a second set of demo links").hasSize(3);
    }

    /**
     * The migrated row is the case the old guard could not see, so the unmigrated one is checked too:
     * the behaviour that already worked must go on working.
     */
    @Test
    void aRestartOverAnUnmigratedRowStillChangesNothing() {
        seeder.run(null);

        seeder.run(null);

        assertThat(storedAccountId()).isEqualTo(DEMO_ACCOUNT);
        assertThat(demoTasks()).hasSize(3);
    }

    /** The first-boot path, unchanged: an empty database still gets the demo clinician and its links. */
    @Test
    void createsTheDemoClinicianAndItsLinksOnAnEmptyDatabase() {
        seeder.run(null);

        Profile seeded = profileRepository.findById(DEMO_PROFESSIONAL_ID).orElse(null);
        assertThat(seeded).isNotNull();
        assertThat(seeded.getAccountId()).isEqualTo(DEMO_ACCOUNT);
        assertThat(seeded.getEmail()).isEqualTo("doctor@localhost");
        assertThat(demoTasks()).extracting(Task::getPatientId).containsExactlyInAnyOrder("patient-kojo", "patient-ophelia", "patient-nana");
    }

    /**
     * A half-seeded database converges. This is what the single early return could never do — it
     * answered "is the profile there?" on behalf of the tasks, which are a different document.
     */
    @Test
    void replacesOnlyTheLinkThatIsMissing() {
        seeder.run(null);
        migrateDemoAccountId();
        taskRepository.deleteAll(demoTasks().stream().filter(task -> "patient-nana".equals(task.getPatientId())).toList());

        seeder.run(null);

        assertThat(demoTasks()).extracting(Task::getPatientId).containsExactlyInAnyOrder("patient-kojo", "patient-ophelia", "patient-nana");
        assertThat(storedAccountId()).as("healing the links must not disturb the migrated key").isEqualTo(MIGRATED_ACCOUNT_ID);
    }

    /**
     * A profile under that {@code _id} that is not the demo clinician's is not overwritten.
     *
     * <p>No path in this service can produce one — {@code POST /api/profiles} refuses (item 66),
     * {@code PUT /api/profiles/{id}} requires the id to exist, and {@code upsertOwnProfile} creates
     * under a generated id — so this pins a decision rather than a reachable state: a seeder that
     * cannot identify its own row writes nothing to it, instead of overwriting a stranger's.
     *
     * <p><b>The links are a separate statement and this test records it rather than hiding it.</b> A
     * task names its clinician by {@code attendantId}, so demo links do attach to whoever holds that
     * id — there is no property of the profile that would let the seeder tell a stranger's row from
     * its own, since {@code accountId} is legitimately a login, a {@code User.id} or null at
     * different points in the migration. Three links, not six: the per-task guard still holds.
     */
    @Test
    void doesNotOverwriteAForeignProfileUnderThatId() {
        Profile stranger = new Profile();
        stranger.setId(DEMO_PROFESSIONAL_ID);
        stranger.setAccountId(MIGRATED_ACCOUNT_ID);
        stranger.setFirstName("Kwesi");
        stranger.setLastName("Owusu");
        profileRepository.save(stranger);

        seeder.run(null);
        seeder.run(null);

        Profile after = profileRepository.findById(DEMO_PROFESSIONAL_ID).orElseThrow();
        assertThat(after.getFirstName()).isEqualTo("Kwesi");
        assertThat(after.getAccountId()).isEqualTo(MIGRATED_ACCOUNT_ID);
        assertThat(demoTasks()).as("links attach to the id, and two runs still produce one set").hasSize(3);
    }
}
