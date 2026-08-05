package net.jojoaddison.repository;

import java.util.List;
import java.util.Optional;
import net.jojoaddison.domain.DeviceToken;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the {@link DeviceToken} document.
 */
@Repository
public interface DeviceTokenRepository extends MongoRepository<DeviceToken, String> {
    Optional<DeviceToken> findByToken(String token);

    List<DeviceToken> findAllByAccountIdAndDisabledAtIsNull(String accountId);

    List<DeviceToken> findAllByAccountId(String accountId);
}
