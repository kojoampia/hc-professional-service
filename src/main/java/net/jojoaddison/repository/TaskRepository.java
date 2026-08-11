package net.jojoaddison.repository;

import net.jojoaddison.domain.Task;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the Task entity.
 */
@SuppressWarnings("unused")
@Repository
public interface TaskRepository extends MongoRepository<Task, String> {
    /**
     * Every task assigned to a clinician. This is professionalservice's half of "has worked with
     * this patient" — the other half is ClinicalCase.assignedProfessionalId, which patientservice
     * owns. attendantId holds a Profile id, not an account login.
     */
    java.util.List<Task> findByAttendantId(String attendantId);
}
