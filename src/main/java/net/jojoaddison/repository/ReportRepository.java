package net.jojoaddison.repository;

import java.util.List;
import net.jojoaddison.domain.Report;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the Report entity.
 */
@SuppressWarnings("unused")
@Repository
public interface ReportRepository extends MongoRepository<Report, String> {
    List<Report> search(String query);
}
