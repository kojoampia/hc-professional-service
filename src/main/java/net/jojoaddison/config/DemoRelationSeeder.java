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
 * <p><strong>Gated on not-production rather than an allow-list of dev and test</strong>, matching
 * {@code InitialSetupMigration} in the gateway and for the same reason: this project's tests run
 * under the profile {@code testdev}, which {@code @Profile({"dev","test"})} would silently exclude —
 * the seed would vanish from the test context and take its coverage with it. Not-prod is the
 * predicate that actually means "everywhere except the deployment the public can reach".
 *
 * <p>Idempotent, like every seeder here: it runs on each start and must leave existing rows alone.
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
        if (profileRepository.findByAccountId(DEMO_ACCOUNT).isPresent()) {
            LOG.debug("Demo clinician profile already present — leaving it and its tasks alone");
            return;
        }

        net.jojoaddison.domain.Profile clinician = new net.jojoaddison.domain.Profile();
        clinician.setId(DEMO_PROFESSIONAL_ID);
        clinician.setAccountId(DEMO_ACCOUNT);
        clinician.setFirstName("Ama");
        clinician.setLastName("Mensah");
        clinician.setEmail("doctor@localhost");
        profileRepository.save(clinician);

        // One task per demo patient. The case half of the union is seeded in patientservice; these
        // exist so the task half is exercised too, and so the directory is not silently dependent
        // on cases alone.
        DEMO_PATIENT_IDS.forEach(patientId -> {
            Task task = new Task();
            task.setName("Demo round for " + patientId);
            task.setAttendantId(DEMO_PROFESSIONAL_ID);
            task.setPatientId(patientId);
            taskRepository.save(task);
        });

        LOG.info(
            "Seeded the demo clinician {} ({}) with {} patient links. The dashboard stays empty until " +
            "patientservice seeds matching profiles and cases — see hc-patient/deploy.",
            DEMO_PROFESSIONAL_ID,
            DEMO_ACCOUNT,
            DEMO_PATIENT_IDS.size()
        );
    }
}
