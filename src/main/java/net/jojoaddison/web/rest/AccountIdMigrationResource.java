package net.jojoaddison.web.rest;

import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.service.AccountIdMigrationService;
import net.jojoaddison.service.GatewayUserClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The one-off operator endpoint that moves every stored account key onto the gateway's
 * {@code User.id} (backlog.md item 50). See {@link AccountIdMigrationService} for what it does and
 * why it is an endpoint rather than a startup runner.
 *
 * <p><b>Under {@code /api/admin}</b>, so {@code SecurityConfiguration}'s rule gates it before the
 * annotation does; the {@code @PreAuthorize} is there so the requirement is legible at the handler
 * rather than only in a config class three files away. Both are load-bearing: the gateway's user
 * table is admin-only since item 55, so a non-admin caller would fail at the far end anyway — with a
 * 403 from a sibling service, which reads as an outage rather than as a permission.
 *
 * <p><b>{@code dryRun} defaults to true.</b> A real run clears the account key on every row it cannot
 * resolve, and an operator should see that count before it happens rather than after.
 *
 * <p>Retire this endpoint and {@code GatewayUserClient} with it once the migration has been run
 * everywhere — they are a tool with one use, in the same way {@code GET /api/users} is (item 55).
 */
@RestController
@RequestMapping("/api/admin")
public class AccountIdMigrationResource {

    private static final Logger log = LoggerFactory.getLogger(AccountIdMigrationResource.class);

    private final AccountIdMigrationService accountIdMigrationService;

    public AccountIdMigrationResource(AccountIdMigrationService accountIdMigrationService) {
        this.accountIdMigrationService = accountIdMigrationService;
    }

    @PostMapping("/account-id-migration")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public AccountIdMigrationService.Report migrate(@RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun) {
        log.info("REST request to run the accountId migration (dryRun={})", dryRun);
        try {
            return accountIdMigrationService.migrate(dryRun);
        } catch (GatewayUserClient.GatewayUnavailableException e) {
            // 503 rather than 500: nothing was written, the request is worth repeating, and the
            // cause is another service. The message names the URL and the status it answered with,
            // because "is the gateway reachable from this container" and "is this caller an admin"
            // are the two things an operator will want to tell apart.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
        }
    }
}
