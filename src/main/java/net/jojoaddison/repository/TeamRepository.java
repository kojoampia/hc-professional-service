package net.jojoaddison.repository;

import net.jojoaddison.domain.Team;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the Team entity.
 */
@SuppressWarnings("unused")
@Repository
public interface TeamRepository extends MongoRepository<Team, String> {}
