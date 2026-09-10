package net.jojoaddison.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jojoaddison.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Reads the gateway's login-to-{@code User.id} table, for the one caller that needs it:
 * {@link AccountIdMigrationService} (backlog.md item 50).
 *
 * <p><b>Why this exists at all.</b> Item 50 moved every {@code accountId} in this database from the
 * gateway login to the gateway's {@code User.id}. The mapping between the two lives in
 * {@code hcProfessionalGateway}'s user store, a database this service does not read and must not
 * start reading — the two products share a Mongo server in some deployments and share nothing
 * architecturally. So the mapping is fetched over HTTP from the service that owns it.
 *
 * <p><b>{@code GET /api/admin/users} and not {@code GET /api/users}, and the difference is the whole
 * point of the endpoint choice.</b> The public one returns {@code {id, login}} with no personal data
 * and would be the obvious pick, but {@code UserService.getAllPublicUsers} filters on
 * {@code activatedIsTrue} — so a suspended or never-activated clinician has a {@code User.id} and is
 * <em>absent from it</em>. Migrating against that table would classify a perfectly resolvable row as
 * unresolvable and clear an account key that had an owner all along, which is worse than either
 * failure this migration is trying to avoid. The admin endpoint reads {@code findAllByIdNotNull} and
 * is therefore total over the local user store, which is the only totality available anywhere.
 *
 * <p>Its DTO carries personal data — email, names — and none of it is bound: {@link GatewayUser}
 * declares two fields and {@code @JsonIgnoreProperties(ignoreUnknown = true)} drops the rest at the
 * parser, so nothing here holds, logs or stores a clinician's details.
 *
 * <p><b>The caller's own token is relayed</b>, exactly as {@link PatientServiceClient} does, for the
 * reason recorded on {@code SecurityUtils.getCurrentUserJWT}: this service mints no tokens and must
 * not learn how. Both gateway endpoints are {@code ROLE_ADMIN} since backlog item 55, so the
 * migration is something an administrator runs rather than something the service does to itself at
 * startup — which is also why it is an endpoint and not an {@code ApplicationRunner} beside
 * {@code ShiftTypeMigration}. A runner has no caller and therefore no credential.
 */
@Service
public class GatewayUserClient {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayUserClient.class);

    /**
     * Spring Data caps a page at 2000 by default and the gateway configures no {@code max-page-size},
     * so anything larger is silently truncated to that — a truncation this class would read as "the
     * account does not exist". Well under it, and paged.
     */
    private static final int PAGE_SIZE = 500;

    /** A stop, so a paging bug cannot walk for ever against a sibling that keeps answering. */
    private static final int MAX_PAGES = 200;

    private static final ParameterizedTypeReference<List<GatewayUser>> USER_LIST = new ParameterizedTypeReference<>() {};

    /** Exactly the two fields this service is entitled to know about a gateway account. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GatewayUser(String id, String login) {}

    /** Raised rather than returning a partial map: a partial map migrates rows onto nothing. */
    public static class GatewayUnavailableException extends RuntimeException {

        public GatewayUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RestClient restClient;
    private final String baseUrl;

    public GatewayUserClient(
        RestClient.Builder builder,
        @Value("${application.gateway.base-url:http://gateway:5505}") String baseUrl,
        @Value("${application.gateway.timeout-seconds:10}") int timeoutSeconds
    ) {
        this.baseUrl = baseUrl;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build()
        );
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * Every local gateway account, as {@code login -> User.id}.
     *
     * <p><b>Lower-cased keys.</b> JHipster lower-cases a login on the way in
     * ({@code UserService.registerUser}), so the stored {@code accountId} and the returned
     * {@code login} agree in practice — but a row written by a fixture or by hand may not, and a
     * case-sensitive miss here would quarantine a resolvable account. The migration lower-cases what
     * it looks up for the same reason.
     *
     * @throws GatewayUnavailableException if any page could not be read. Deliberately not a partial
     *     result: a missing page is indistinguishable from a set of deleted accounts, and the
     *     migration's answer to those two is opposite.
     */
    public Map<String, String> loginToAccountId() {
        String token = SecurityUtils.getCurrentUserJWT().orElse(null);
        if (token == null) {
            throw new GatewayUnavailableException("No caller token to relay; the gateway user table is admin-only", null);
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            List<GatewayUser> batch = page(token, page);
            if (batch == null || batch.isEmpty()) {
                LOG.info("Read {} gateway accounts from {} in {} page(s)", mapping.size(), baseUrl, page);
                return mapping;
            }
            for (GatewayUser user : batch) {
                if (user.id() != null && user.login() != null && !user.login().isBlank()) {
                    mapping.put(user.login().toLowerCase(java.util.Locale.ROOT), user.id());
                }
            }
            if (batch.size() < PAGE_SIZE) {
                LOG.info("Read {} gateway accounts from {} in {} page(s)", mapping.size(), baseUrl, page + 1);
                return mapping;
            }
        }
        throw new GatewayUnavailableException("Gateway user table did not end after " + MAX_PAGES + " pages", null);
    }

    private List<GatewayUser> page(String token, int page) {
        try {
            return restClient
                .get()
                .uri(uri -> uri.path("/api/admin/users").queryParam("page", page).queryParam("size", PAGE_SIZE).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(USER_LIST);
        } catch (Exception e) {
            // The HTTP status is worth naming: a 403 here means the caller is not an admin, which is
            // an operator error with an obvious fix, while a connect failure means the gateway is
            // unreachable from this container and is not.
            throw new GatewayUnavailableException(
                "Could not read page " + page + " of the gateway user table at " + baseUrl + ": " + e.getMessage(),
                e
            );
        }
    }
}
