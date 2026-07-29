package net.jojoaddison.repository;

import java.util.List;
import net.jojoaddison.domain.DutyRoster;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for professional duty-roster assignments.
 */
@Repository
public interface DutyRosterRepository extends MongoRepository<DutyRoster, String> {
    List<DutyRoster> findByProfessionalIdOrderByDateAscShiftAsc(String professionalId);

    List<DutyRoster> findAllByOrderByDateAscShiftAsc();
}
