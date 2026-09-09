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

    private final OrganizationReferenceValidator organizationReferenceValidator;

    public ProfileService(ProfileRepository profileRepository, OrganizationReferenceValidator organizationReferenceValidator) {
        this.profileRepository = profileRepository;
        this.organizationReferenceValidator = organizationReferenceValidator;
    }

    /*
     * There is deliberately no `save` here, and it is the second generated method on this entity to
     * go — backlog.md item 66, after item 56 took the delete.
     *
     * Its only caller was `ProfileResource.createProfile`, which now refuses: since item 54 made
     * `accountId` READ_ONLY over HTTP, a create from a request body could only produce a profile
     * belonging to nobody, and nothing could afterwards give it an owner. The argument is on that
     * handler.
     *
     * What made it worth removing rather than leaving unused is that it was the one write path to
     * this collection with no rule on it at all: `update` preserves accountId/accountUid/createdDate
     * from the stored row and runs `OrganizationReferenceValidator`, `partialUpdate` merges field by
     * field, `upsertOwnProfile` forces the account to the caller. A bare repository passthrough on
     * the service is what the next create would have been written against.
     *
     * The two paths that legitimately create a profile do it through the repository, each with its
     * own reason recorded: `OnboardingService.upsertOwnProfile` (the clinician's own, from their
     * token) and `updatePushPreferences` below (the caller's own, so a toggle works before
     * onboarding finishes).
     */

    /**
     * Update a profile.
     *
     * @param profile the entity to save.
     * @return the persisted entity.
     */
    public Profile update(Profile profile) {
        log.debug("Request to update Profile : {}", profile);
        // A whole-document replace, so anything the request body omits is dropped. Two fields must
        // survive that, and both are invisible to the caller by design (backlog item 48's review):
        //
        //   accountUid — READ_ONLY over HTTP, so a client CANNOT send it and every PUT would
        //                otherwise clear it. Profile.accountUid says it "is never cleared once set";
        //                without this line that was true of upsertOwnProfile and false here, and the
        //                published ProfileStatus would flip a known uid to null — exactly what
        //                aPreClaimTokenDoesNotClearAUidTheProfileAlreadyLearnt exists to prevent.
        //   createdDate — @CreatedDate is not re-applied to an entity that already has an id, so a
        //                 replace persists whatever the body carried, which is normally nothing.
        //
        // Read from the stored row rather than trusted from the body: the body is the caller's, the
        // row is the service's.
        Profile stored = profileRepository.findById(profile.getId()).orElse(null);
        if (stored != null) {
            // accountId first, and for a harder reason than the other two: it is the ownership
            // check. READ_ONLY stops a client *sending* one; this stops a PUT that omits it from
            // clearing the field and detaching the clinician from their own documents.
            profile.setAccountId(stored.getAccountId());
            profile.setAccountUid(stored.getAccountUid());
            profile.setCreatedDate(stored.getCreatedDate());
        }
        // A specialty or team this write *introduces* must name a row that exists (backlog item 60).
        // Verified reachable before this line existed: on the quality stack a PUT carrying a
        // fabricated category id and a fabricated team id returned 200 and stored both, producing by
        // typo exactly the dangling pointer item 57 stopped a delete from producing.
        organizationReferenceValidator.requireIntroducedReferencesResolve(profile, stored);
        return profileRepository.save(profile);
    }

    /**
     * Partially update a profile.
     *
     * <p><b>Thirteen fields, and the other five are guaranteed absent rather than ignored here.</b>
     * This copied eleven and stopped, so a merge-patch naming {@code title},
     * {@code emergencyContact}, {@code specialtyCategoryId}, {@code teamIds} or one of the three push
     * preferences answered 200 with the row unchanged and the unmodified profile as the body —
     * backlog.md item 60. {@code title} and {@code emergencyContact} join the eleven below;
     * {@link net.jojoaddison.web.rest.ProfileResource} refuses the other five with a 400 before this
     * method is reached, and the argument for that split lives there because it is an argument about
     * the HTTP surface.
     *
     * <p>So this method is deliberately <em>not</em> the place that guards the five: it cannot be.
     * Whether a merge-patch <em>named</em> a field is a fact about the JSON document, and by the time
     * a {@link Profile} has been bound an absent {@code teamIds} and an explicitly empty one are the
     * same empty list — the field is initialised, so a {@code != null} guard of the shape used below
     * would fire on every patch and empty a clinician's teams whenever they changed their phone
     * number. Only the resource, which still holds the raw node, can tell the two apart.
     *
     * <p>{@code .jhipster/Profile.json} lists {@code title}, {@code emergencyContact},
     * {@code specialtyCategoryId} and {@code teamIds}, so a regeneration would re-emit all four into
     * this if-chain and quietly undo the refusal. {@code ProfilePatchFieldCoverageIT} is what fails
     * when that happens.
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
                if (profile.getTitle() != null) {
                    existingProfile.setTitle(profile.getTitle());
                }
                // Whole-object replace, not a recursive merge, which is a deviation from RFC 7396 —
                // the media type this endpoint consumes — and a deliberate one.
                //
                // Not because a merge is impossible. It used to be: the body was bound to a Profile
                // before it reached any of this, so an absent "phone" and an explicit "phone": null
                // arrived as the same Java null. THIS COMMIT ENDED THAT — ProfileResource now holds
                // the raw ObjectNode, so a recursive merge is reconstructible there and could be
                // handed down. It simply is not, for the two reasons that were always the real ones:
                // address above, the other embedded object partialUpdate copies, has always replaced
                // wholesale, and OnboardingService.upsertOwnProfile replaces this very field. A
                // merge here would make the two paths that write emergencyContact disagree about
                // what writing it means.
                if (profile.getEmergencyContact() != null) {
                    existingProfile.setEmergencyContact(profile.getEmergencyContact());
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
