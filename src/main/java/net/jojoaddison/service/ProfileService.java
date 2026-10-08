package net.jojoaddison.service;

import java.util.Optional;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.ProfileStatus;
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
     * this collection with no rule on it at all: `update` preserves accountId and createdDate
     * from the stored row and runs `OrganizationReferenceValidator`, `partialUpdate` merges field by
     * field, `partialUpdateOwnProfile` forces the account to the caller. A bare repository passthrough on
     * the service is what the next create would have been written against.
     *
     * The paths that legitimately create a profile do it through the repository, each with its own
     * reason recorded: `partialUpdateOwnProfile` below (the caller's own, because profile.md's step 2
     * IS "create the profile" and PUT /api/profile is the endpoint it names) and
     * `updatePushPreferences` below (the caller's own, so a toggle works before onboarding finishes).
     * `OnboardingService.upsertOwnProfile` was a third until F8 retired it with
     * /api/onboarding/profile.
     *
     * There were two until profile.md's T1 and this sentence said so; it is a count maintained by
     * hand, so prefer the shared property to the tally — EVERY ONE OF THEM FORCES accountId FROM THE
     * CALLER'S TOKEN AND NONE TAKES IT FROM A BODY. That is the invariant item 54 exists for, and a
     * fourth writer that honours it is fine while a third that did not would be the takeover again.
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
        //   accountId — READ_ONLY over HTTP, so a client CANNOT send it and every PUT would
        //               otherwise clear it. It is also the ownership check, which is a harder reason
        //               than the other: READ_ONLY stops a client *sending* one, and this stops a PUT
        //               that omits it from detaching the clinician from their own documents.
        //   createdDate — @CreatedDate is not re-applied to an entity that already has an id, so a
        //                 replace persists whatever the body carried, which is normally nothing.
        //
        // There was a third, accountUid, until backlog item 50 folded it into accountId — the two
        // held the same value once accountId became the gateway's User.id.
        //
        // Read from the stored row rather than trusted from the body: the body is the caller's, the
        // row is the service's.
        Profile stored = profileRepository.findById(profile.getId()).orElse(null);
        if (stored != null) {
            profile.setAccountId(stored.getAccountId());
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
     * <p><b>The fields this method does not copy are guaranteed absent rather than ignored here.</b>
     * This copied eleven and stopped, so a merge-patch naming {@code title},
     * {@code contacts}, {@code specialtyCategoryId}, {@code teamIds} or one of the three push
     * preferences answered 200 with the row unchanged and the unmodified profile as the body —
     * backlog.md item 60. {@code title} and {@code contacts} join the rest in
     * {@link #applyProvidedFields}; {@code ProfileFieldOwnership.REFUSED_FIELDS} names the others and
     * refuses each with a 400 before this method is reached, and the argument for that split lives
     * there because it is an argument about the HTTP surface. Read that map rather than a count: the
     * set grew by {@code status} after this sentence was first written.
     *
     * <p>So this method is deliberately <em>not</em> the place that guards them: it cannot be.
     * Whether a merge-patch <em>named</em> a field is a fact about the JSON document, and by the time
     * a {@link Profile} has been bound an absent {@code teamIds} and an explicitly empty one are the
     * same empty list — the field is initialised, so a {@code != null} guard of the shape
     * {@link #applyProvidedFields} uses would fire on every patch and empty a clinician's teams
     * whenever they changed their phone number. Only the resource, which still holds the raw node,
     * can tell the two apart.
     *
     * <p>{@code .jhipster/Profile.json} lists {@code title}, {@code contacts},
     * {@code specialtyCategoryId} and {@code teamIds}, so a regeneration would re-emit all four into
     * the if-chain below and quietly undo the refusal. {@code ProfilePatchFieldCoverageIT} is what
     * fails when that happens.
     *
     * @param profile the entity to update partially.
     * @return the persisted entity.
     */
    public Optional<Profile> partialUpdate(Profile profile) {
        log.debug("Request to partially update Profile : {}", profile);

        return profileRepository
            .findById(profile.getId())
            .map(existing -> applyProvidedFields(existing, profile))
            .map(profileRepository::save);
    }

    /**
     * The caller's own profile, partially written — {@code PUT /api/profile} (profile.md step 2, T1).
     *
     * <h2>Partial, and the wizard is the reason rather than a preference</h2>
     *
     * <p>{@code profile.md} specifies <b>a dialog panel per step</b>, so a pane saves only its own
     * slice of the document. A whole-document write is therefore wrong <em>by construction</em>: the
     * next-of-kin pane would blank the address the address pane had just saved, with a 200 and a body
     * confirming it. That is the behaviour this method <b>replaced rather than inherited</b> —
     * {@code OnboardingService.upsertOwnProfile}, retired by F8, wrote thirteen fields
     * unconditionally with no {@code != null} guards, and the clients only survived it by sending
     * <code>{...this.loaded, …}</code> back, which made correctness a property of the client.
     *
     * <h2>{@code accountId} is forced here and never read from the body</h2>
     *
     * <p>Twice over, deliberately. {@code Profile.accountId} is {@code @JsonProperty(READ_ONLY)} so a
     * client cannot send one at all (backlog.md item 54, a live account takeover) — and this method
     * sets it from the caller's token regardless, because <b>a defence that depends on a Jackson
     * annotation staying put is one edit from being gone</b> and this is the field every ownership
     * check in the service resolves through. A {@code PUT} from account A can therefore not reach
     * account B's row by any route: the lookup is by the caller's own account id, and the value
     * written is the caller's own account id.
     *
     * <h2>It creates the row when there is none, and that is step 2 rather than an upsert habit</h2>
     *
     * <p>Step 2 <em>is</em> "create the profile", so refusing a write for want of an existing row
     * would make the specified flow unreachable. The create takes the shape
     * {@link #updatePushPreferences} already uses — a document holding nothing but the account id,
     * then the provided fields on top — which is also why that method is the precedent for the next
     * paragraph.
     *
     * <p><b>No {@code entity.created} is published, and that is the house decision rather than an
     * omission.</b> {@code ProfileStatusAnnouncer}'s javadoc states it in as many words:
     * {@code updatePushPreferences} creates a profile and announces no creation, backlog.md item 49
     * names that, and it is left alone because {@code entity.created} carries an {@code actor} and an
     * {@code accountId} that are not derivable from the saved document, and rides
     * {@code hc.professional.entity}, which hc-admin is not subscribed to. The profile's arrival is
     * not lost: the save raises {@code AfterSaveEvent} and {@code ProfileStatus} is announced from
     * there, which is the half the estate actually consumes.
     *
     * <h2>It stamps {@code PROFILE_COMPLETED}, and the server is the only thing that may (F1)</h2>
     *
     * <p>{@code profile.md} § "Step 2": <i>"Set {@code profile.status} to {@code PROFILE_COMPLETED}
     * when every field is provided"</i>. {@link ProfileCompleteness} is what "every field" means —
     * 39 values on the owner's literal, nested reading — and it is evaluated <b>after</b> the merge,
     * against the stored row as it now stands, never against the request body: a pane saves one
     * slice, so the body alone can never be complete and a predicate run over it would stamp
     * nothing, ever.
     *
     * <p><b>The client never decides this.</b> {@code status} is in
     * {@code ProfileFieldOwnership.REFUSED_FIELDS}, so a body naming it is refused with a 400 before
     * this method is reached — an applicant who could write {@code status} would approve their own
     * credential review. The two halves are one rule read from both ends: the caller cannot set it
     * and the server always does.
     *
     * <p>⚠ <b>It sets, and does not clear.</b> {@code profile.md} says what to do when every field
     * is provided and says nothing about the other case, so nothing here invents a second rule — a
     * profile that was complete keeps its {@code status} if a later write leaves it short. ⛔ Do not
     * add a clearing branch on the reasoning that it is tidier: {@code ProfileStatus} is the
     * alphabet of {@code OnboardingService}'s state machine and a profile may legitimately have been
     * moved past {@code PROFILE_COMPLETED} by an admin transition, which this method must not undo.
     * Narrowing the field back from {@code CREDENTIAL_REVIEW} because a clinician blanked a middle
     * name would rewind an application's lifecycle from a form.
     *
     * @param accountId the caller's gateway {@code User.id}, from the {@code uid} claim.
     * @param incoming the fields the caller named; everything null is left as stored.
     * @return the persisted profile.
     */
    public Profile partialUpdateOwnProfile(String accountId, Profile incoming) {
        log.debug("Request to partially update own Profile for account : {}", accountId);
        Profile own = profileRepository.findByAccountId(accountId).orElseGet(Profile::new);
        own.setAccountId(accountId);
        Profile merged = applyProvidedFields(own, incoming);
        if (ProfileCompleteness.isComplete(merged)) {
            merged.setStatus(ProfileStatus.PROFILE_COMPLETED);
        }
        return profileRepository.save(merged);
    }

    /**
     * Copies every field the caller provided onto the stored row, leaving the rest alone.
     *
     * <p><b>One copy of this list, called from both write paths</b>, which is the point of it being a
     * method (profile.md T1). {@code PATCH /api/profiles/&#123;id&#125;} and
     * {@code PUT /api/profile} apply the same fields and refuse the same ones
     * ({@code ProfileFieldOwnership}); two if-chains would be two answers to the same question, and a
     * field added to one is the drift {@code quality/}'s items 84 and 92 are a record of.
     *
     * @param target the stored row, or a fresh document owned by the caller.
     * @param provided the bound request body.
     */
    private Profile applyProvidedFields(Profile target, Profile provided) {
        if (provided.getFirstName() != null) {
            target.setFirstName(provided.getFirstName());
        }
        // Copied since profile.md's T1, and NO APPLICANT-FACING WRITE PATH had ever copied it:
        // upsertOwnProfile omitted it, so the middle name the wizard collected was stored by nothing
        // while profile.md's header renders the full name.
        //
        // ⛔ THAT CLAIM USED TO READ "it had never been written by ANY path" AND THAT WAS FALSE (F6).
        // The admin PATCH /api/profiles/{id} did copy it, through this very method, before T1 —
        // `git show 53e9640^:src/main/java/net/jojoaddison/service/ProfileService.java` lines 123-124.
        // The narrower claim is the true one and is the one that mattered: an applicant filling in
        // their own profile had no route by which the field reached the database. Corrected here
        // rather than reworded, because a comment that overstates a gap is how the next reader
        // concludes a field is unreachable when it is merely unreachable from one direction.
        if (provided.getMiddleNames() != null) {
            target.setMiddleNames(provided.getMiddleNames());
        }
        if (provided.getLastName() != null) {
            target.setLastName(provided.getLastName());
        }
        if (provided.getBirthDate() != null) {
            target.setBirthDate(provided.getBirthDate());
        }
        if (provided.getSex() != null) {
            target.setSex(provided.getSex());
        }
        if (provided.getMobilePhone() != null) {
            target.setMobilePhone(provided.getMobilePhone());
        }
        if (provided.getPhoneNumber() != null) {
            target.setPhoneNumber(provided.getPhoneNumber());
        }
        if (provided.getEmail() != null) {
            target.setEmail(provided.getEmail());
        }
        if (provided.getCardType() != null) {
            target.setCardType(provided.getCardType());
        }
        if (provided.getCardNumber() != null) {
            target.setCardNumber(provided.getCardNumber());
        }
        if (provided.getAddress() != null) {
            target.setAddress(provided.getAddress());
        }
        if (provided.getTitle() != null) {
            target.setTitle(provided.getTitle());
        }
        // Whole-object replace, not a recursive merge, which is a deviation from RFC 7396 —
        // the media type the PATCH endpoint consumes — and a deliberate one.
        //
        // Not because a merge is impossible. It used to be: the body was bound to a Profile
        // before it reached any of this, so an absent "phone" and an explicit "phone": null
        // arrived as the same Java null. ITEM 60's COMMIT ENDED THAT — ProfileResource holds
        // the raw ObjectNode, so a recursive merge is reconstructible there and could be
        // handed down. It simply is not, for the two reasons that were always the real ones:
        // address above, the other embedded object this copies, has always replaced
        // wholesale, and the retired upsertOwnProfile replaced this very field. A merge
        // here would make the paths that write the next of kin disagree about what writing
        // it means — there is one such path left, and this method is it, so the rule is now
        // kept for the PATCH's sake rather than for a sibling writer's.
        //
        // THE LIST DOES NOT CHANGE THAT ARGUMENT, it sharpens it (profile.md T1). `contacts`
        // replaces wholesale as `emergencyContact` did, and a per-element merge would have to
        // answer "which element" — on a collection with no id on its members and no stable
        // order, that question has no answer a client could predict. Replacing the list is the
        // only rule both write paths can mean the same thing by.
        //
        // AND THIS GUARD IS WHY Profile.contacts IS NOT INITIALISED TO AN EMPTY LIST. Were it
        // initialised, as teamIds is, `provided.getContacts()` would never be null and every
        // partial write would empty the clinician's next of kin — the defect
        // ProfilePatchFieldCoverageIT.anAbsentTeamIdsIsNotAChange exists for, one field over.
        if (provided.getContacts() != null) {
            target.setContacts(provided.getContacts());
        }
        return target;
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
     * <p>This deliberately does not go through {@link #partialUpdateOwnProfile}, which is the write
     * {@code PUT /api/profile} serves: routing preferences through it would mean sending a whole
     * profile to change one toggle, and the three flags are refused there by
     * {@code ProfileFieldOwnership} so that this method is their only writer. ⚠ Until F8 the
     * argument was stronger — {@code OnboardingService.upsertOwnProfile} set every field it knew
     * from the body, so the alternative blanked the rest.
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
