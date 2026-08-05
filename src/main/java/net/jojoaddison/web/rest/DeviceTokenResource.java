package net.jojoaddison.web.rest;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import net.jojoaddison.domain.DeviceToken;
import net.jojoaddison.repository.DeviceTokenRepository;
import net.jojoaddison.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Device registrations for push (MOB9).
 *
 * <p><b>Security note.</b> {@code /api/notifications/**} is declared {@code authenticated()} in
 * {@link net.jojoaddison.config.SecurityConfiguration} <em>before</em> the
 * {@code POST /api/**} rule that requires {@code CLINICAL_MUTATION}. Without that, a carer, angel,
 * chemist or technician would get a 403 registering a device — silently, forever, with no error
 * they could act on. Registering for notifications is not a clinical mutation.
 */
@RestController
@RequestMapping("/api/notifications")
public class DeviceTokenResource {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenResource.class);

    private final DeviceTokenRepository deviceTokenRepository;

    public DeviceTokenResource(DeviceTokenRepository deviceTokenRepository) {
        this.deviceTokenRepository = deviceTokenRepository;
    }

    /**
     * Registers or refreshes this device's token.
     *
     * <p><b>A token conflict reassigns the account rather than rejecting.</b> FCM reuses a
     * registration token when a second user signs in on the same handset, so a stale mapping would
     * deliver clinician A's notifications to clinician B. This is the single most important
     * correctness detail in the feature.
     */
    @PostMapping("/devices")
    public ResponseEntity<DeviceToken> register(@RequestBody DeviceRegistration registration) {
        String accountId = currentAccount();
        if (registration.getToken() == null || registration.getToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A device token is required");
        }

        DeviceToken device = deviceTokenRepository.findByToken(registration.getToken()).orElseGet(DeviceToken::new);

        if (device.getId() != null && !accountId.equals(device.getAccountId())) {
            log.info("Device token reassigned from {} to {}", device.getAccountId(), accountId);
        }

        device.setToken(registration.getToken());
        device.setAccountId(accountId);
        device.setPlatform(registration.getPlatform());
        device.setAppVersion(registration.getAppVersion());
        device.setLangKey(registration.getLangKey());
        device.setLastSeenAt(Instant.now());
        // A re-registration revives a token previously pruned as dead: the app is plainly installed.
        device.setDisabledAt(null);
        device.setDisabledReason(null);
        if (device.getCreatedDate() == null) {
            device.setCreatedDate(Instant.now());
        }

        DeviceToken saved = deviceTokenRepository.save(device);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /** Deregisters a device. Called on sign-out, before credentials are cleared. */
    @DeleteMapping("/devices/{token}")
    public ResponseEntity<Void> deregister(@PathVariable String token) {
        String accountId = currentAccount();
        deviceTokenRepository
            .findByToken(token)
            // Only your own. Deleting by token alone would let anyone who learned a token
            // silence somebody else's notifications.
            .filter(device -> accountId.equals(device.getAccountId()))
            .ifPresent(deviceTokenRepository::delete);
        return ResponseEntity.noContent().build();
    }

    /** The caller's own registered devices, for a "signed-in devices" view. */
    @GetMapping("/devices")
    public List<DeviceToken> myDevices() {
        return deviceTokenRepository.findAllByAccountId(currentAccount());
    }

    private String currentAccount() {
        return SecurityUtils.getCurrentUserLogin()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
    }

    /** Registration request. Carries no credential — an FCM token authorises nothing here. */
    public static class DeviceRegistration {

        @NotBlank
        private String token;

        private String platform;
        private String appVersion;
        private String langKey;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getPlatform() {
            return platform;
        }

        public void setPlatform(String platform) {
            this.platform = platform;
        }

        public String getAppVersion() {
            return appVersion;
        }

        public void setAppVersion(String appVersion) {
            this.appVersion = appVersion;
        }

        public String getLangKey() {
            return langKey;
        }

        public void setLangKey(String langKey) {
            this.langKey = langKey;
        }
    }
}
