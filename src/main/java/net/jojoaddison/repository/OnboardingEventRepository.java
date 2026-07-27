package net.jojoaddison.repository;

import java.util.List;
import net.jojoaddison.domain.OnboardingEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the append-only OnboardingEvent audit
 * trail. Callers must only insert and read; the WP3 service layer exposes no
 * update or delete path.
 */
@Repository
public interface OnboardingEventRepository extends MongoRepository<OnboardingEvent, String> {
    List<OnboardingEvent> findByApplicationIdOrderByAtAsc(String applicationId);
}
