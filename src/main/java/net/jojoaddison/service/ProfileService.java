package net.jojoaddison.service;

import java.util.Optional;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.repository.ProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Service Implementation for managing {@link net.jojoaddison.domain.Profile}.
 */
@Service
public class ProfileService {

    private final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final ProfileRepository profileRepository;

    public ProfileService(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    /**
     * Save a profile.
     *
     * @param profile the entity to save.
     * @return the persisted entity.
     */
    public Profile save(Profile profile) {
        log.debug("Request to save Profile : {}", profile);
        return profileRepository.save(profile);
    }

    /**
     * Update a profile.
     *
     * @param profile the entity to save.
     * @return the persisted entity.
     */
    public Profile update(Profile profile) {
        log.debug("Request to update Profile : {}", profile);
        return profileRepository.save(profile);
    }

    /**
     * Partially update a profile.
     *
     * @param profile the entity to update partially.
     * @return the persisted entity.
     */
    public Optional<Profile> partialUpdate(Profile profile) {
        log.debug("Request to partially update Profile : {}", profile);

        return profileRepository
            .findById(profile.getId())
            .map(existingProfile -> {
                if (profile.getFirstName() != null) {
                    existingProfile.setFirstName(profile.getFirstName());
                }
                if (profile.getMiddleNames() != null) {
                    existingProfile.setMiddleNames(profile.getMiddleNames());
                }
                if (profile.getLastName() != null) {
                    existingProfile.setLastName(profile.getLastName());
                }
                if (profile.getBirthDate() != null) {
                    existingProfile.setBirthDate(profile.getBirthDate());
                }
                if (profile.getSex() != null) {
                    existingProfile.setSex(profile.getSex());
                }
                if (profile.getMobilePhone() != null) {
                    existingProfile.setMobilePhone(profile.getMobilePhone());
                }
                if (profile.getPhoneNumber() != null) {
                    existingProfile.setPhoneNumber(profile.getPhoneNumber());
                }
                if (profile.getEmail() != null) {
                    existingProfile.setEmail(profile.getEmail());
                }
                if (profile.getCardType() != null) {
                    existingProfile.setCardType(profile.getCardType());
                }
                if (profile.getCardNumber() != null) {
                    existingProfile.setCardNumber(profile.getCardNumber());
                }
                if (profile.getAddress() != null) {
                    existingProfile.setAddress(profile.getAddress());
                }

                return existingProfile;
            })
            .map(profileRepository::save);
    }

    /**
     * Get all the profiles.
     *
     * @param pageable the pagination information.
     * @return the list of entities.
     */
    public Page<Profile> findAll(Pageable pageable) {
        log.debug("Request to get all Profiles");
        return profileRepository.findAll(pageable);
    }

    /**
     * Get one profile by id.
     *
     * @param id the id of the entity.
     * @return the entity.
     */
    public Optional<Profile> findOne(String id) {
        log.debug("Request to get Profile : {}", id);
        return profileRepository.findById(id);
    }

    /**
     * Get one profile by email.
     *
     * @param email the email of the entity.
     * @return the entity.
     */
    public Optional<Profile> findByEmail(String email) {
        log.debug("Request to get Profile : {}", email);
        return profileRepository.findByEmail(email);
    }

    /**
     * Get one profile by accountId.
     *
     * @param accountId the accountId of the entity.
     * @return the entity.
     */
    public Optional<Profile> findByAccountId(String accountId) {
        log.debug("Request to get Profile : {}", accountId);
        return profileRepository.findByAccountId(accountId);
    }

    /**
     * Count all profiles.
     *
     * @return the number of profiles.
     */
    public long count() {
        log.debug("Request to count all Profiles");
        return profileRepository.count();
    }

    /**
     * Delete the profile by id.
     *
     * @param id the id of the entity.
     */
    public void delete(String id) {
        log.debug("Request to delete Profile : {}", id);
        profileRepository.deleteById(id);
    }

    /**
     * Whether this account wants message pushes. Absent preference means yes, so existing
     * profiles keep working without a migration.
     */
    public boolean wantsMessagePush(String accountId) {
        return findByAccountId(accountId).map(p -> !Boolean.FALSE.equals(p.getPushMessagesEnabled())).orElse(true);
    }

    /** Whether this account wants compliance pushes. Absent preference means yes. */
    public boolean wantsCompliancePush(String accountId) {
        return findByAccountId(accountId).map(p -> !Boolean.FALSE.equals(p.getPushComplianceEnabled())).orElse(true);
    }

    /**
     * Whether the sender's name may appear on the lock screen.
     *
     * <p>Defaults to FALSE, unlike the two above: a notification preview is visible to anyone
     * holding the phone, so revealing a colleague's name has to be chosen, not inherited.
     */
    public boolean wantsSenderNameInPush(String accountId) {
        return findByAccountId(accountId).map(p -> Boolean.TRUE.equals(p.getPushShowSenderName())).orElse(false);
    }

    /** The three push preferences, with the defaults applied. Never null (MOB10). */
    public PushPreferences pushPreferences(String accountId) {
        return new PushPreferences(wantsMessagePush(accountId), wantsCompliancePush(accountId), wantsSenderNameInPush(accountId));
    }

    /**
     * Writes the three push preferences and returns what is now stored.
     *
     * <p><b>Creates a profile if the account has none.</b> A clinician can install the app and open
     * settings before completing onboarding, and a toggle that flips back on the next screen visit
     * is worse than no toggle. The document created holds nothing but the account id and the flags;
     * onboarding fills in the rest and does not treat mere existence as progress — the application
     * advances only when {@code completeProfile} is called explicitly.
     *
     * <p>This deliberately does not go through {@code OnboardingService.upsertOwnProfile}. That sets
     * every field it knows from the incoming body, so routing preferences through it would mean
     * either sending a whole profile to change one toggle, or blanking the rest.
     */
    public PushPreferences updatePushPreferences(String accountId, PushPreferences preferences) {
        Profile profile = profileRepository.findByAccountId(accountId).orElseGet(() -> new Profile().accountId(accountId));
        profile.setPushMessagesEnabled(preferences.messages());
        profile.setPushComplianceEnabled(preferences.compliance());
        profile.setPushShowSenderName(preferences.showSenderName());
        profileRepository.save(profile);
        return pushPreferences(accountId);
    }

    /**
     * How this clinician wants to be notified, following them across devices.
     *
     * <p>{@code showSenderName} defaults to false while the other two default to true: a lock screen
     * is visible to anyone holding the phone, so revealing even a colleague's name is chosen rather
     * than inherited.
     */
    public record PushPreferences(boolean messages, boolean compliance, boolean showSenderName) {}
}
