package net.jojoaddison.repository;

import java.util.Optional;
import net.jojoaddison.domain.PatientWriteReceipt;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for {@link PatientWriteReceipt}.
 *
 * <p>Lookup is by {@code clientRef} alone; the account and patient on the stored receipt are checked
 * by the caller rather than folded into the query, so a key replayed by the wrong account is a
 * detectable fault rather than a silent miss that files a second record.
 */
@Repository
public interface PatientWriteReceiptRepository extends MongoRepository<PatientWriteReceipt, String> {
    Optional<PatientWriteReceipt> findByClientRef(String clientRef);
}
