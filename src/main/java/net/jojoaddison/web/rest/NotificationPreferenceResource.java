package net.jojoaddison.web.rest;

import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.ProfileService;
import net.jojoaddison.service.ProfileService.PushPreferences;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The signed-in clinician's own notification preferences (MOB10).
 *
 * <p><b>Why this is not part of the profile endpoint.</b> The preferences live on {@code Profile},
 * as the plan requires — they follow the clinician across devices rather than sitting on a
 * {@code DeviceToken} — but {@code PUT /api/onboarding/profile} sets every field it knows from the
 * body it is given. Sending three toggles through it would blank the clinician's address and
 * identity card; sending the whole profile back to change one toggle makes a settings switch depend
 * on a successful profile read. A small endpoint that writes only these three fields avoids both.
 *
 * <p><b>It sits under {@code /api/notifications/**} for the security rule.</b> That prefix is
 * declared {@code authenticated()} in {@link net.jojoaddison.config.SecurityConfiguration}
 * <em>above</em> the {@code PUT /api/**} rule that requires {@code CLINICAL_MUTATION}. Anywhere else
 * a carer, angel, chemist or technician — every read-only role — would get a silent 403 turning
 * their own notifications off, which is the same trap the device registration endpoint was moved
 * out of.
 *
 * <p>There is no admin view of anybody else's preferences and there should not be: the account is
 * always the caller's, taken from the token and never from the body.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationPreferenceResource {

    private final ProfileService profileService;

    public NotificationPreferenceResource(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * The caller's preferences, with defaults applied.
     *
     * <p>200 with the defaults rather than 404 when no profile exists yet: the client is asking what
     * would happen if a notification arrived now, and the answer is well defined before onboarding
     * completes.
     */
    @GetMapping("/preferences")
    public PushPreferences myPreferences() {
        return profileService.pushPreferences(currentAccount());
    }

    /** Replaces all three. A partial body would be ambiguous with "off". */
    @PutMapping("/preferences")
    public PushPreferences update(@RequestBody PushPreferences preferences) {
        if (preferences == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Preferences are required");
        }
        return profileService.updatePushPreferences(currentAccount(), preferences);
    }

    private String currentAccount() {
        return SecurityUtils.getCurrentUserLogin()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
    }
}
