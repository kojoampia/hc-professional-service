package net.jojoaddison.config;

import java.util.List;
import net.jojoaddison.domain.Task;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds the professional-to-patient relation for local work, never in production.
 *
 * <p><strong>Only the relation.</strong> professionalservice owns which clinician is attached to
 * which patient; the patients and clinical cases themselves belong to patientservice and are seeded
 * there. So this creates a demo clinician's profile and the tasks linking them to patient ids — and
 * on its own it produces an empty dashboard, because the demographics those ids resolve to live in
 * the sibling stack. Both halves have to be seeded for the demo to show anything, and the ids below
 * are the contract between them.
 *
 * <p>The profile matters more than the tasks. {@code PatientDirectoryService} resolves the caller
 * through {@code Profile.findByAccountId}, so without a profile for the {@code doctor} account the
 * directory is empty no matter what else exists.
 *
 * <p><strong>It seeds the login, and since backlog.md item 50 that is not what a caller resolves
 * to.</strong> {@code Profile.accountId} now holds the gateway's {@code User.id}, and this runner
 * cannot know it: the gateway's {@code InitialSetupMigration} gives each seeded professional a fresh
 * {@code UUID.randomUUID()}, so there is no constant to seed against and no way to ask — this class
 * runs at startup with no caller and therefore no token to read the gateway's user table with. The
 * login is seeded deliberately, and one call to
 * {@code POST /api/admin/account-id-migration?dryRun=false} as an administrator moves it onto the
 * real id, exactly as it does for a real deployment's stored rows. Until that call the demo
 * clinician's directory is empty — which is the same symptom as a missing profile and is worth
 * knowing before debugging it as one.
 *
 * <p><strong>Gated on not-production rather than an allow-list of dev and test</strong>, matching
 * {@code InitialSetupMigration} in the gateway and for the same reason: this project's tests run
 * under the profile {@code testdev}, which {@code @Profile({"dev","test"})} would silently exclude —
 * the seed would vanish from the test context and take its coverage with it. Not-prod is the
 * predicate that actually means "everywhere except the deployment the public can reach".
 *
 * <p><strong>Idempotent, like every seeder here — and idempotent per row, because a guard on one
 * document cannot speak for another.</strong> It ran until backlog.md item 118 behind a single early
 * return that asked {@code findByAccountId("doctor")} while the write it protected collided on
 * {@code _id: professional-doctor}. Those named the same row only while {@code accountId} held the
 * login; item 50 moved that field onto the gateway's {@code User.id}, so the lookup found nothing,
 * the save landed on the same {@code _id} anyway and <em>set {@code accountId} back to the login</em>
 * — reverting the migration for this row on every boot, and appending a second, third and fourth set
 * of tasks as it went. It was silent: the row is written, no request fails, and the only visible
 * effect is that the demo clinician's directory answers {@code 200} with nothing in it, because
 * {@code SecurityUtils.getCurrentAccountId()} resolves a {@code uid} and there is deliberately no
 * fallback to the login.
 *
 * <p>So each write is now guarded on the identity <em>that write</em> collides on: the profile on its
 * {@code _id}, and each task on the {@code (attendantId, patientId)} pair that makes it the link it
 * is — tasks carry generated ids, so they collide on nothing and their identity has to be stated. The
 * shape is deliberately convergent rather than all-or-nothing: a database holding the profile and no
 * tasks gets its tasks, which the single early return could never do. The cost is that a demo task
 * deleted through {@code TaskResource} comes back on the next start. That is the right trade for a
 * fixture gated on not-production, and the wrong one for anything a clinician authored — which is why
 * nothing here overwrites a row it did not write.
 */
@Component
@Profile("!prod")
public class DemoRelationSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoRelationSeeder.class);

    /** Must match the {@code assignedProfessionalId} on the demo cases seeded in patientservice. */
    private static final String DEMO_PROFESSIONAL_ID = "professional-doctor";

    /** The demo clinician's account, seeded by the gateway outside production. */
    private static final String DEMO_ACCOUNT = "doctor";

    /** Must match {@code Profile.patientId} on the demo profiles seeded in patientservice. */
    private static final List<String> DEMO_PATIENT_IDS = List.of("patient-kojo", "patient-ophelia", "patient-nana");

    private final ProfileRepository profileRepository;
    private final TaskRepository taskRepository;

    public DemoRelationSeeder(ProfileRepository profileRepository, TaskRepository taskRepository) {
        this.profileRepository = profileRepository;
        this.taskRepository = taskRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean profileCreated = seedProfile();
        int tasksCreated = seedTasks();

        if (!profileCreated && tasksCreated == 0) {
            LOG.debug("Demo clinician {} and its {} patient links are already present", DEMO_PROFESSIONAL_ID, DEMO_PATIENT_IDS.size());
            return;
        }
        LOG.info(
            "Seeded the demo clinician {} ({}): profile {}, {} of {} patient links added. The dashboard " +
            "stays empty until patientservice seeds matching profiles and cases — see hc-patient/deploy.",
            DEMO_PROFESSIONAL_ID,
            DEMO_ACCOUNT,
            profileCreated ? "created" : "already present",
            tasksCreated,
            DEMO_PATIENT_IDS.size()
        );
    }

    /**
     * Creates the demo clinician's profile if no row holds its id.
     *
     * <p><b>Keyed on {@code _id}, which is what the save collides on</b>, and not on {@code accountId}
     * — see the class javadoc for what asking the wrong question cost. The two readings of "already
     * seeded" differ in exactly one case, and it is the case that matters: a row whose {@code _id} is
     * this one but whose {@code accountId} has moved to a {@code User.id}. That row is seeded and
     * migrated, and is left alone here.
     *
     * <p><b>Nothing else in this service can put a row under that {@code _id}</b>, so leaving it alone
     * cannot be leaving somebody else's row alone: {@code POST /api/profiles} refuses outright (item
     * 66), {@code PUT /api/profiles/{id}} requires the id to exist already, and
     * {@code OnboardingService.upsertOwnProfile} either adopts the row it finds by {@code accountId}
     * or creates one under a generated id. The demo account is therefore the only account this row can
     * ever belong to, and a hand-edited database that made it otherwise is still better served by a
     * seeder that writes nothing than by one that overwrites a stranger's profile.
     *
     * <p><b>The login is still what goes in {@code accountId} on a create</b>, deliberately and for the
     * reason the class javadoc gives: this runner has no caller, so it cannot resolve the gateway's
     * {@code User.id} — {@code GatewayUserClient.loginToAccountId()} relays an administrator's own
     * token and refuses without one. Seeding {@code null} instead would be worse than seeding the
     * login, because {@code AccountIdMigrationService} only walks rows whose key is non-null: an
     * ownerless row is one nothing can ever repair, whereas a login is a value the migration resolves.
     *
     * @return whether a profile was created.
     */
    private boolean seedProfile() {
        if (profileRepository.existsById(DEMO_PROFESSIONAL_ID)) {
            return false;
        }
        net.jojoaddison.domain.Profile clinician = new net.jojoaddison.domain.Profile();
        clinician.setId(DEMO_PROFESSIONAL_ID);
        clinician.setAccountId(DEMO_ACCOUNT);
        clinician.setFirstName("Ama");
        clinician.setLastName("Mensah");
        clinician.setEmail("doctor@localhost");
        profileRepository.save(clinician);
        return true;
    }

    /**
     * Creates one task per demo patient, for whichever of them has none yet.
     *
     * <p>The case half of the union is seeded in patientservice; these exist so the task half is
     * exercised too, and so the directory is not silently dependent on cases alone.
     *
     * <p><b>A task carries a generated id, so it collides on nothing and repeats rather than
     * replacing.</b> Its identity is the link it expresses — this clinician, this patient — so that is
     * what the guard reads. Deriving it from the rows already stored rather than from whether the
     * profile exists is the point: the profile's presence was the old guard, and it is a statement
     * about a different document.
     *
     * <p>{@code findByAttendantId} is the existing finder and the whole set is three rows on a demo
     * database, so the patient ids are matched in memory rather than by adding a repository method the
     * generator would delete.
     *
     * @return how many links were created.
     */
    private int seedTasks() {
        List<String> linked = taskRepository.findByAttendantId(DEMO_PROFESSIONAL_ID).stream().map(Task::getPatientId).toList();
        int created = 0;
        for (String patientId : DEMO_PATIENT_IDS) {
            if (linked.contains(patientId)) {
                continue;
            }
            Task task = new Task();
            task.setName("Demo round for " + patientId);
            task.setAttendantId(DEMO_PROFESSIONAL_ID);
            task.setPatientId(patientId);
            taskRepository.save(task);
            created++;
        }
        return created;
    }
}
