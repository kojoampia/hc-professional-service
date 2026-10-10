package net.jojoaddison.repository;

import net.jojoaddison.domain.AccountCompleteness;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the step-1 projection (backlog.md row 230, unit A).
 *
 * <p><b>No derived query methods, and that is the point.</b> The account id is the document's
 * {@code _id}, so {@code findById} is the only lookup there is and a "latest row for this account"
 * query — the shape that goes wrong when two frames race — cannot be written against it. See
 * {@link AccountCompleteness}.
 */
@Repository
public interface AccountCompletenessRepository extends MongoRepository<AccountCompleteness, String> {}
