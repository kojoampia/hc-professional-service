package net.jojoaddison.repository;

import net.jojoaddison.domain.Roster;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the Roster entity.
 */
@SuppressWarnings("unused")
@Repository
public interface RosterRepository extends MongoRepository<Roster, String> {}
