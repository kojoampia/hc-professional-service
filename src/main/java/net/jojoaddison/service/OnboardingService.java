package net.jojoaddison.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.domain.OnboardingEvent;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.domain.enumeration.VerificationStatus;
import net.jojoaddison.repository.OnboardingEventRepository;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.service.dto.OnboardingProgressDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Onboarding application state machine (professional-onboarding-workflow.md
 * § Status model). Every transition is validated server-side and recorded as
 * an append-only {@link OnboardingEvent}; illegal transitions are rejected
 * with 409 CONFLICT.
 *
 * <p><b>Account linkage.</b> {@code accountId} is the gateway's {@code User.id}, read from the
 * {@code uid} claim by {@code SecurityUtils.getCurrentAccountId()}. It was the JWT subject — the
 * login — from WP1 until 2026-09-10, which is what this javadoc's "switch to User.id once the
 * gateway adds a uid claim" had been waiting for since WP1 and what backlog.md item 50 did.
 *
 * <p>Two things about that are easy to get wrong afterwards. <b>The login has not become a
 * fallback</b>: a caller whose token carries no claim resolves to nobody and is refused, because a
 * second identifier accepted in the same field is the fork the change removed. And <b>the login is
 * still the right value for {@code OnboardingEvent.actor}</b> and for every event {@code actor}
 * field, which name a person in a trail and are never looked up — the two are taken from different
 * accessors on purpose, and {@code OnboardingResource} keeps them apart.
 */
@Service
public class OnboardingService {

    /**
     * Refusal reason when a SUSPENDED application tries to go ACTIVE without a current licence.
     *
     * <p>Public because the integration tests assert it, and they should assert the same string the
     * path throws rather than a copy of it — a reword must not be able to break a test silently.
     * {@link ComplianceService#LICENSE_EXPIRED_REASON} is the same idea for the sweep's audit reason.
     */
    public static final String REACTIVATION_REQUIRES_LICENSE = "Reactivation requires a verified, unexpired license";

    /** Refusal prefix when a transition to ACTIVE fails the eight-requirement completion contract; the missing keys follow. */
    public static final String ACTIVATION_REQUIRES_COMPLETE_PROFILE = "Activation requires a complete profile";

    /**
     * Refusal prefix when step 4's submit is attempted with a requirement unsatisfied; the missing
     * keys follow (F-B).
     *
     * <p>Public and asserted by the integration tests rather than copied into them, for the reason
     * {@link #REACTIVATION_REQUIRES_LICENSE} gives: a reword must not be able to break a test
     * silently. It is deliberately a <em>different</em> sentence from
     * {@link #ACTIVATION_REQUIRES_COMPLETE_PROFILE} — that one refuses an administrator activating
     * somebody, this one refuses the applicant submitting themselves, and the two gates read
     * different definitions of complete.
     */
    public static final String SUBMISSION_REQUIRES_ALL_REQUIREMENTS = "Submission requires every onboarding requirement to be satisfied";

    /**
     * Refusal when {@code agreed} is false on any of the three writes that record consent.
     *
     * <p>Public and shared by all three rather than reworded per endpoint: there is one fact —
     * consent was not given — and three places that may discover it, so a second wording would be a
     * second copy of the same rule with nothing holding them in step. The integration tests assert
     * this constant rather than a copy of its text.
     */
    public static final String CONSENT_REQUIRED = "Consent must be accepted to start an application";

    /**
     * Refusal prefix when a write names an {@code authority} that is not one of the eight professional
     * disciplines; the accepted values follow.
     *
     * <p>Public and asserted rather than copied, for the reason {@link #CONSENT_REQUIRED} gives. It
     * names the field because the three writes that can raise it carry two other components and a
     * caller has to be told which one was refused.
     */
    public static final String AUTHORITY_MUST_BE_A_PROFESSIONAL_DISCIPLINE = "authority must be one of the professional disciplines";

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    private static final Set<DocumentType> IDENTITY_TYPES = EnumSet.of(
        DocumentType.PASSPORT,
        DocumentType.GHANACARD,
        DocumentType.DRIVERLICENSE,
        DocumentType.VOTERCARD
    );

    private static final Map<ProfileStatus, Set<ProfileStatus>> LEGAL_TRANSITIONS = Map.ofEntries(
        Map.entry(ProfileStatus.APPLICATION_STARTED, EnumSet.of(ProfileStatus.PROFILE_COMPLETED)),
        Map.entry(ProfileStatus.PROFILE_COMPLETED, EnumSet.of(ProfileStatus.CREDENTIAL_REVIEW)),
        Map.entry(
            ProfileStatus.CREDENTIAL_REVIEW,
            EnumSet.of(ProfileStatus.APPROVED, ProfileStatus.REJECTED, ProfileStatus.RETURNED_FOR_CORRECTION)
        ),
        Map.entry(ProfileStatus.RETURNED_FOR_CORRECTION, EnumSet.of(ProfileStatus.PROFILE_COMPLETED, ProfileStatus.CREDENTIAL_REVIEW)),
        Map.entry(
            ProfileStatus.APPROVED,
            EnumSet.of(ProfileStatus.ORGANIZATION_ASSIGNED, ProfileStatus.SUSPENDED, ProfileStatus.EXPIRED, ProfileStatus.DEACTIVATED)
        ),
        Map.entry(
            ProfileStatus.ORGANIZATION_ASSIGNED,
            EnumSet.of(ProfileStatus.AUTHORITY_ASSIGNED, ProfileStatus.SUSPENDED, ProfileStatus.EXPIRED, ProfileStatus.DEACTIVATED)
        ),
        Map.entry(
            ProfileStatus.AUTHORITY_ASSIGNED,
            EnumSet.of(ProfileStatus.ROSTER_CONFIGURED, ProfileStatus.SUSPENDED, ProfileStatus.EXPIRED, ProfileStatus.DEACTIVATED)
        ),
        Map.entry(
            ProfileStatus.ROSTER_CONFIGURED,
            EnumSet.of(ProfileStatus.ACTIVE, ProfileStatus.SUSPENDED, ProfileStatus.EXPIRED, ProfileStatus.DEACTIVATED)
        ),
        Map.entry(ProfileStatus.ACTIVE, EnumSet.of(ProfileStatus.SUSPENDED, ProfileStatus.EXPIRED, ProfileStatus.DEACTIVATED)),
        Map.entry(ProfileStatus.SUSPENDED, EnumSet.of(ProfileStatus.ACTIVE, ProfileStatus.EXPIRED, ProfileStatus.DEACTIVATED)),
        Map.entry(ProfileStatus.EXPIRED, EnumSet.of(ProfileStatus.CREDENTIAL_REVIEW, ProfileStatus.DEACTIVATED)),
        Map.entry(ProfileStatus.REJECTED, EnumSet.noneOf(ProfileStatus.class)),
        Map.entry(ProfileStatus.DEACTIVATED, EnumSet.noneOf(ProfileStatus.class))
    );

    private final ProfessionalApplicationRepository applicationRepository;
    private final OnboardingEventRepository eventRepository;
    private final ProfileRepository profileRepository;
    private final PersonalDocumentRepository personalDocumentRepository;
    private final DomainEventPublisher domainEventPublisher;

    private final OrganizationReferenceValidator organizationReferenceValidator;

    public OnboardingService(
        ProfessionalApplicationRepository applicationRepository,
        OnboardingEventRepository eventRepository,
        ProfileRepository profileRepository,
        PersonalDocumentRepository personalDocumentRepository,
        DomainEventPublisher domainEventPublisher,
        OrganizationReferenceValidator organizationReferenceValidator
    ) {
        this.applicationRepository = applicationRepository;
        this.eventRepository = eventRepository;
        this.profileRepository = profileRepository;
        this.personalDocumentRepository = personalDocumentRepository;
        this.domainEventPublisher = domainEventPublisher;
        this.organizationReferenceValidator = organizationReferenceValidator;
    }

    /**
     * Creates the caller's application, recording step 4's consent and requested authority
     * (profile.md step 4; {@code POST /api/professional-application}).
     *
     * @param accountId the caller's gateway {@code User.id} — the key this application is found by.
     * @param login the caller's login, stored beside it as the human-readable name. Until item 50
     *     both fields were written from one value, because both <em>were</em> the login; they are
     *     now two identifiers and the caller passes each explicitly.
     * @param authority the role string being applied for. Named {@code requestedRole} until T3;
     *     {@code profile.md} § Gap Update renamed it and kept it a {@code String}, because
     *     {@code Authority} is the gateway's class and this service holds only the role. <b>Refused
     *     with 400 unless it is one of the eight professional disciplines</b>, or absent — see
     *     {@link #refuseAnAuthorityThatIsNotADiscipline}, which is also where "absent is not invalid"
     *     is argued.
     * @param agreed the consent tick. Named {@code consentAccepted} until T3.
     */
    public ProfessionalApplication startApplication(
        String accountId,
        String login,
        String authority,
        boolean agreed,
        String invitedBy,
        String source
    ) {
        if (!agreed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CONSENT_REQUIRED);
        }
        refuseAnAuthorityThatIsNotADiscipline(authority);
        applicationRepository
            .findByAccountId(accountId)
            .ifPresent(existing -> {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An application already exists for this account");
            });
        ProfessionalApplication application = applicationRepository.save(
            new ProfessionalApplication()
                .accountId(accountId)
                .login(login)
                .authority(authority)
                // profile.md step 4: "profileId | string | Set to Profile.id". Null when the
                // applicant has no profile yet, which is legal and is what completeProfile fills in;
                // every reader already answers 409 "Application has no linked profile" for that.
                .profileId(ownProfileId(accountId))
                .status(ProfileStatus.APPLICATION_STARTED)
                .agreed(true)
                .agreedDate(Instant.now())
                .invitedBy(invitedBy)
                .source(normalizeSource(source))
        );
        appendEvent(application, null, ProfileStatus.APPLICATION_STARTED, "application started");
        domainEventPublisher.publishEntityCreated(
            "ProfessionalApplication",
            application.getId(),
            application.getAccountId(),
            net.jojoaddison.security.SecurityUtils.getCurrentUserLogin().orElse("system")
        );
        return application;
    }

    /*
     * upsertOwnProfile is GONE — retired by F8 with GET and PUT /api/onboarding/profile, per
     * profile.md § Other Elements: "api/onboarding/profile should migrate to api/profile".
     *
     * ProfileService.partialUpdateOwnProfile is the write now, and it is NOT this method made
     * partial. This one set thirteen fields unconditionally with no `!= null` guard, so a wizard
     * pane saving its own slice blanked every field the other panes had written; the replacement
     * applies only what the caller named, creates the row on first write, and stamps
     * PROFILE_COMPLETED when ProfileCompleteness says every field is provided.
     *
     * TWO THINGS THAT WENT WITH IT, both worth knowing before writing a fixture:
     *
     *   The entity.created publication. This method published one for a profile it created, and
     *   partialUpdateOwnProfile deliberately does not — the house decision recorded on
     *   ProfileStatusAnnouncer, where updatePushPreferences has created profiles silently since
     *   MOB9: entity.created carries an actor and an accountId that are not derivable from the
     *   saved document and rides hc.professional.entity, which hc-admin is not subscribed to. The
     *   profile's arrival is still announced — the save raises AfterSaveEvent and ProfileStatus goes
     *   out from there, which is the half the estate consumes.
     *
     *   A FALSE CLAIM THIS JAVADOC CARRIED (F6). It said middleNames was copied by "no write path
     *   anywhere" before T1. The admin PATCH /api/profiles/{id} did copy it — see
     *   ProfileService.applyProvidedFields, where the narrower and true claim is now recorded: no
     *   APPLICANT-FACING write path copied it.
     */

    /**
     * Composes and publishes {@code ProfileStatus} — the second half of a clinician's arrival, for a
     * sibling directory that has the account half and nothing to complete it with.
     *
     * <p><b>This composes the frame; it does not decide when one is due.</b> Its only caller is
     * {@link ProfileStatusAnnouncer}, which listens for the persisted document. It was called from a
     * table of four paths until 2026-09-08, and the two paths that were not on the table — a
     * clinician renewing their own licence, and the whole {@code PersonalDocumentResource} CRUD
     * surface — are backlog.md item 49: an upload adds a {@code PENDING} row, so it takes
     * {@code isVerified} to false and said nothing. A list of call sites cannot fail when a fifth one
     * is written, so there is no longer a list.
     *
     * <p>The composition is here rather than in the broker because the broker layer takes primitives
     * only: {@code TechnicalStructureTest} puts {@code ..broker..} in no layer, so a class in it may
     * reference neither {@code ..service..} nor {@code ..domain..}, and reading a document
     * collection is both.
     *
     * <p><b>Both booleans are read from the definitions that already exist</b> rather than derived
     * again here. That is not tidiness: two derivations of one rule disagreeing has been a
     * production defect in this file before (backlog.md item 17), and a directory on another stack
     * showing "complete" while the {@code ACTIVE} gate refuses the same profile would be that defect
     * with an audience.
     *
     * <p><b>Publishing must never break the write path.</b> The profile is saved by the time this
     * runs, and the reads it makes are as capable of failing as the send is, so the whole of it is
     * guarded rather than only the send — {@code DomainEventPublisher} catches its own broker
     * failures and cannot catch a repository throwing on the way in.
     */
    public void publishProfileStatus(Profile profile) {
        if (profile == null || profile.getAccountId() == null) {
            // A profile with no account cannot be correlated with anything on the far side, so the
            // event would be a row nobody could ever attach. ProfileResource accepts a client-built
            // Profile, so this is reachable rather than defensive.
            return;
        }
        try {
            domainEventPublisher.publishProfileStatus(
                // Off the profile row, never off the caller: most paths into here are an
                // administrator acting on somebody else's profile, so the calling token's uid would
                // name the wrong person. This read `profile.getAccountUid()` until item 50, beside a
                // login-valued accountId that the contract's `accountId` did not mean; the two
                // identifier spaces are now one and the field the contract names is the field the
                // row holds.
                profile.getAccountId(),
                profile.getId(),
                progressFor(profile.getAccountId()).complete(),
                allLiveDocumentsVerified(profile.getId()),
                profile.getCreatedDate(),
                profile.getModifiedDate(),
                profile.getLastModifiedBy()
            );
        } catch (RuntimeException e) {
            log.error("Could not announce ProfileStatus for {} — the write it followed stands", profile.getAccountId(), e);
        }
    }

    /**
     * {@link #publishProfileStatus(Profile)} for a caller that holds a profile id rather than a
     * profile — which {@link ProfileStatusAnnouncer} always does, since a document names its profile
     * and not the row.
     */
    public void publishProfileStatusFor(String profileId) {
        profileRepository.findById(profileId).ifPresent(this::publishProfileStatus);
    }

    /**
     * Every live document on this profile verified, and at least one present.
     *
     * <p>The profile-scoped form of what {@link #requireAllMandatoryDocumentsVerified} enforces, and
     * that method now asks this rather than repeating the rule — one definition, for the reason
     * {@link #isCurrentVerifiedLicense} gives at length. Archived rows are excluded: a superseded
     * upload is credential history, not something still awaiting a reviewer.
     */
    public boolean allLiveDocumentsVerified(String profileId) {
        if (profileId == null) {
            return false;
        }
        List<PersonalDocument> live = personalDocumentRepository
            .findByProfileId(profileId)
            .stream()
            .filter(PersonalDocumentService::isLive)
            .toList();
        return !live.isEmpty() && live.stream().allMatch(d -> d.getVerificationStatus() == VerificationStatus.VERIFIED);
    }

    /** Attribution is a short opaque label; cap it so the field can't be abused as free storage. */
    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String trimmed = source.trim();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }

    public ProfessionalApplication getOwnApplication(String accountId) {
        return applicationRepository
            .findByAccountId(accountId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No application for this account"));
    }

    public ProfessionalApplication completeProfile(String accountId) {
        ProfessionalApplication application = getOwnApplication(accountId);
        Profile profile = profileRepository
            .findByAccountId(accountId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No profile exists for this account yet"));
        application.profileId(profile.getId());
        return transition(application, ProfileStatus.PROFILE_COMPLETED, accountId, "profile completed");
    }

    /**
     * <b>Step 4's Save</b> — stores the consent and the requested authority, and advances to
     * {@code CREDENTIAL_REVIEW} <em>only</em> when every requirement is satisfied (profile.md step 4
     * and § Gap Update; owner decision 2026-10-09).
     *
     * <h2>⭐ Save is non-advancing when the application is incomplete, and that is not an error</h2>
     *
     * <p>The owner's words: <i>"Save should be non-advancing — store answers only"</i>, refined by
     * <i>"Save advances only when complete"</i>. So this path <b>always stores</b>, and an incomplete
     * application gets <b>200 with the application as stored</b> — no transition, no Kafka event and
     * <b>no 400</b>. An applicant filling the wizard in several sittings has to be able to keep their
     * answers, and a refusal is the wrong answer to "I am not finished yet".
     *
     * <p>⛔ <b>This replaces the reading T3 shipped, and the argument T3 made for it is left here
     * because it was half right.</b> That javadoc said {@code profile.md} step 4 — <i>"A <b>Save</b>
     * and a <b>Submit</b> button store the consent and the requested authority, and set
     * {@code application.status} to {@code CREDENTIAL_REVIEW}"</i> — is one sentence with a compound
     * subject attributing identical effects to both buttons, so there was one service method and the
     * difference was the wizard's. It then refused a Kafka-less Save on the ground that a status
     * change the estate is never told about is <b>a consumer reading where nobody writes</b>: hc-admin
     * consumes {@code onboarding.state COMPLETED} to learn an application is waiting on a reviewer.
     * <b>That half still holds and is honoured here</b> — Save does not reach
     * {@code CREDENTIAL_REVIEW} quietly; when it advances it publishes, exactly as Submit does. What
     * was wrong was treating "identical effects" as "identical preconditions".
     *
     * <p>⚠ <b>Repeatable, which is the property the one-method shape could not have.</b> Save is
     * callable any number of times: it checks whether the move is legal from the current status
     * <em>before</em> transitioning rather than letting {@link #transition} refuse, so a second Save,
     * and a Save on an application already in {@code CREDENTIAL_REVIEW}, store and return 200. The
     * 409 belongs to Submit, which is where {@code aSecondWriteIsRefusedByTheStateMachine} now lives.
     *
     * @param accountId the caller's gateway {@code User.id}.
     * @param agreed step 4's consent tick; {@code false} is refused on both paths — a withheld tick
     *     is a different answer from an unfinished form.
     * @param authority the role string being applied for. A value outside the eight professional
     *     disciplines is refused with 400 on both paths, before anything is stored — see
     *     {@link #refuseAnAuthorityThatIsNotADiscipline}. ⭐ <b>A body naming <em>no</em> authority
     *     leaves the stored one untouched and still answers 200</b> (owner decision 2026-10-09: <i>"Save
     *     should store what I named — don't blank it"</i>); blank counts as naming none.
     */
    public ProfessionalApplication saveConsent(String accountId, boolean agreed, String authority) {
        return storeThenAdvanceWhenComplete(accountId, agreed, authority, false);
    }

    /**
     * <b>Step 4's Submit</b> — stores the same two answers, then <b>requires</b> every requirement
     * this service can see and refuses naming the unsatisfied ones (profile.md § Gap Update: <i>"…
     * when all requirements are satisfied"</i>).
     *
     * <h2>⛔ "All requirements" means all of them, not step 3's documents (F-B)</h2>
     *
     * <p>This checked {@code requireMandatoryDocuments} and nothing else, and the transition before
     * it — {@link #completeProfile} — requires only that a {@code Profile} <em>row exist</em>. So an
     * applicant with all four documents and a blank {@code phoneNumber}, {@code digitalAddress},
     * {@code town} or {@code district}, or one emergency contact instead of two, got <b>200</b> here,
     * reached {@code CREDENTIAL_REVIEW}, and had {@code onboarding.state COMPLETED} published to
     * hc-admin — <b>and then {@link #markStatus}({@code ACTIVE}) refused with
     * {@link #ACTIVATION_REQUIRES_COMPLETE_PROFILE} after a reviewer had done the work</b>, with
     * nothing at any point having told the applicant their profile was short.
     *
     * <p>⚠ <b>Step 1's four account fields are the gateway's and this service cannot see them.</b>
     * {@code firstName}, {@code lastName}, {@code langKey} and {@code imageUrl} live on {@code User}
     * in {@code hcProfessionalGateway}; there is deliberately <b>no cross-service call invented
     * here</b> to read them, so what this gate enforces is steps 2, 3 and 4. A submission whose
     * account is incomplete still passes, and the client is what keeps step 1 ahead of step 2. Raised
     * with the owner rather than guessed at.
     *
     * @see #saveConsent the same storing and the same completeness evaluation, without the refusal
     */
    public ProfessionalApplication submitForReview(String accountId, boolean agreed, String authority) {
        return storeThenAdvanceWhenComplete(accountId, agreed, authority, true);
    }

    /**
     * The whole of step 4's write, shared by both buttons: store, then advance if complete.
     *
     * <p><b>One body rather than two, deliberately.</b> The owner's instruction is explicit that the
     * storing and the completeness evaluation must not be duplicated between the paths — and this
     * repository's own notes say why in general terms: two correct-for-now copies is how an estate
     * arrives at one wrong one, and {@code quality/}'s items 84, 91 and 92 are a fix applied to one of
     * two copies. So the only thing the two paths disagree about is {@code refuseWhenIncomplete},
     * which is the single difference the owner's table draws.
     *
     * @param refuseWhenIncomplete Submit passes {@code true} and refuses with
     *     {@link #SUBMISSION_REQUIRES_ALL_REQUIREMENTS} naming the unsatisfied keys; Save passes
     *     {@code false} and returns the stored application unadvanced.
     */
    private ProfessionalApplication storeThenAdvanceWhenComplete(
        String accountId,
        boolean agreed,
        String authority,
        boolean refuseWhenIncomplete
    ) {
        if (!agreed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CONSENT_REQUIRED);
        }
        // Before the lookup, as the consent check above already is: both are questions about the body,
        // and a refused body should not depend on whether the caller has an application yet.
        refuseAnAuthorityThatIsNotADiscipline(authority);
        ProfessionalApplication application = getOwnApplication(accountId);

        // --- Store. On both paths, and BEFORE any completeness question: the owner's "Save should be
        // non-advancing — store answers only" makes keeping the answers the one thing this write
        // always does.
        //
        // ⭐ The authority is written ONLY when the body NAMES one — the owner's "Save should store
        // what I named — don't blank it" (2026-10-09). This write was unconditional, so a body of
        // {"agreed":true} ERASED a role the applicant had previously declared and answered 200: the
        // field a reviewer acts on, cleared by a client that merely saved the consent tick. A Save
        // stores the fields it names and leaves the others as they are.
        //
        // ⚠ Blank counts as not-named, by the same hasText the submission gate and
        // refuseAnAuthorityThatIsNotADiscipline already use — one definition of absence in this file
        // rather than a second one that would make "" a clear where it is a 400 or a no-op elsewhere.
        // ⚠ And an explicit "authority": null is a NO-OP rather than a clear, because
        // ApplicationConsentRequest is a record: after binding, absent and null are the same value
        // and no code here can tell them apart. ProfileResource's PATCH keeps the raw ObjectNode to
        // solve exactly that, and restructuring this endpoint the same way is a larger change than
        // the decision asked for — see ProfessionalApplicationResource.ApplicationConsentRequest,
        // which records the limit beside the record it is a property of. Nothing clears a declared
        // authority today, which is the state the owner asked for.
        //
        // The consent DATE is not re-stamped over an existing one. profile.md renders it — "dated
        // Application.agreedDate" — and a re-affirmation of a consent already given is not a new
        // consent, so moving the date would make the page state something untrue about when the
        // subject agreed. That holds across a Save-then-Submit sequence as much as across two Saves.
        if (hasText(authority)) {
            application.authority(authority);
        }
        if (!application.isAgreed()) {
            application.agreed(true).agreedDate(Instant.now());
        }
        if (application.getProfileId() == null) {
            application.profileId(ownProfileId(accountId));
        }

        // --- Evaluate, once, for both paths. ⚠ Against the authority now STORED, not the one the
        // body named: since a body naming none no longer blanks the field, the two differ, and asking
        // about the body would refuse a Submit with "authority" missing from an application that
        // plainly carries one — a refusal naming a requirement the applicant has already met.
        List<String> missing = unsatisfiedRequirements(accountId, application.getAuthority());
        if (!missing.isEmpty()) {
            if (refuseWhenIncomplete) {
                // Nothing is saved: the refusal is the whole answer, and a Submit that stored and
                // then 400ed would leave the caller unable to tell which of the two happened.
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    SUBMISSION_REQUIRES_ALL_REQUIREMENTS + "; still missing: " + String.join(", ", missing)
                );
            }
            return applicationRepository.save(application);
        }

        // --- Advance. Save asks whether the move is legal first so that it can be called twice;
        // Submit lets the state machine answer, which is where the 409 belongs.
        boolean legal = LEGAL_TRANSITIONS.getOrDefault(application.getStatus(), Set.of()).contains(ProfileStatus.CREDENTIAL_REVIEW);
        if (!legal && !refuseWhenIncomplete) {
            // A complete application that is already in CREDENTIAL_REVIEW, or past it — the answers
            // are stored and there is nothing to announce a second time. Publishing again would send
            // hc-admin a COMPLETED for an application it has already queued.
            return applicationRepository.save(application);
        }

        application.submittedAt(Instant.now());
        ProfessionalApplication saved = transition(
            application,
            ProfileStatus.CREDENTIAL_REVIEW,
            accountId,
            "submitted for credential review"
        );
        // COMPLETED means the applicant is done, not that they are cleared to work — the ACTIVE
        // event says that. Keeping them apart is what lets the admin portal tell an application
        // stalled on us from one stalled on the clinician.
        domainEventPublisher.publishOnboardingState("COMPLETED", accountId, saved.getId(), saved.getAuthority(), accountId);
        return saved;
    }

    /**
     * The caller's own {@code Profile.id}, or null when they have none yet.
     *
     * <p>Resolved here rather than taken from a request body, for the reason
     * {@code ProfileFieldOwnership} gives about every other linking field: a client-supplied
     * {@code profileId} would let an applicant attach their application to a colleague's profile,
     * and the reviewer's document list is keyed on exactly that value.
     */
    private String ownProfileId(String accountId) {
        return profileRepository.findByAccountId(accountId).map(Profile::getId).orElse(null);
    }

    public ProfessionalApplication decide(
        String applicationId,
        ProfileStatus decision,
        String reason,
        String correctionNotes,
        String actor
    ) {
        if (decision != ProfileStatus.APPROVED && decision != ProfileStatus.REJECTED && decision != ProfileStatus.RETURNED_FOR_CORRECTION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Decision must be APPROVED, REJECTED or RETURNED_FOR_CORRECTION");
        }
        if (decision != ProfileStatus.APPROVED && (reason == null || reason.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rejection or correction requires a reviewer reason");
        }
        ProfessionalApplication application = getById(applicationId);
        if (decision == ProfileStatus.APPROVED) {
            requireAllMandatoryDocumentsVerified(application);
        }
        application.decidedBy(actor).decidedAt(Instant.now()).decisionReason(reason).correctionNotes(correctionNotes);
        return transition(application, decision, actor, reason == null ? "approved" : reason);
    }

    public ProfessionalApplication assignOrganization(
        String applicationId,
        String specialtyCategoryId,
        List<String> teamIds,
        String supervisorProfileId,
        String actor
    ) {
        ProfessionalApplication application = getById(applicationId);
        Profile profile = profileRepository
            .findById(application.getProfileId() == null ? "" : application.getProfileId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Application has no linked profile"));
        // Before the write and before the transition: this is *the* assignment operation, so an id
        // that names nothing is a mistake worth refusing outright rather than one to be tolerated
        // because the profile already held it (backlog item 60). Nothing is saved and the application
        // does not advance.
        organizationReferenceValidator.requireReferencesResolve(specialtyCategoryId, teamIds);
        profile.specialtyCategoryId(specialtyCategoryId);
        if (teamIds != null) {
            profile.teamIds(teamIds);
        }
        profileRepository.save(profile);
        log.debug("Organization context assigned to profile {} (supervisor {})", profile.getId(), supervisorProfileId);
        return transition(application, ProfileStatus.ORGANIZATION_ASSIGNED, actor, "organization context assigned");
    }

    public ProfessionalApplication markStatus(String applicationId, ProfileStatus target, String reason, String actor) {
        ProfessionalApplication application = getById(applicationId);
        if (target == ProfileStatus.ACTIVE) {
            // A profile goes ACTIVE only when it is complete AND vetted. The vetting half is the
            // APPROVED -> ... -> ACTIVE chain, which only an admin can drive; this is the other
            // half, and it is checked here rather than in the client because an admin activating an
            // incomplete application is a bug, not a shortcut.
            requireCompleteProfile(application);
            // WP7 reactivation guard: leaving SUSPENDED additionally requires a current license.
            if (application.getStatus() == ProfileStatus.SUSPENDED) {
                requireCurrentVerifiedLicense(application);
            }
        }
        ProfessionalApplication saved = transition(application, target, actor, reason);
        if (target == ProfileStatus.ACTIVE) {
            domainEventPublisher.publishOnboardingState("ACTIVE", saved.getAccountId(), saved.getId(), saved.getAuthority(), actor);
        }
        return saved;
    }

    private void requireCompleteProfile(ProfessionalApplication application) {
        OnboardingProgressDTO progress = progressFor(application.getAccountId());
        if (!progress.complete()) {
            String missing = progress
                .requirements()
                .stream()
                .filter(requirement -> !requirement.done())
                .map(OnboardingProgressDTO.Requirement::key)
                .collect(java.util.stream.Collectors.joining(", "));
            throw new ResponseStatusException(HttpStatus.CONFLICT, ACTIVATION_REQUIRES_COMPLETE_PROFILE + "; still missing: " + missing);
        }
    }

    private void requireCurrentVerifiedLicense(ProfessionalApplication application) {
        // isCurrentVerifiedLicense screens archived rows itself, so the unfiltered list is safe here.
        if (documentsFor(application).stream().noneMatch(OnboardingService::isCurrentVerifiedLicense)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, REACTIVATION_REQUIRES_LICENSE);
        }
    }

    /**
     * The single definition of "this licence is current": a verified LICENSE carrying an expiry date
     * that has not passed.
     *
     * <p>It is one method rather than one per caller because the two callers used to disagree, and
     * the disagreement was a production defect (backlog.md item 17). Reactivation asked "is there a
     * current licence" while {@link ComplianceService#sweepExpiredLicenses} asked "is there an
     * expired one", and after a renewal <em>both were true at once</em> — a renewal adds a document
     * and nothing retires the lapsed one — so an administrator's reinstatement was undone by the
     * 04:00 sweep, with an audit trail blaming a licence that was valid.
     *
     * <p>An archived row is not a current licence whatever its dates say (backlog.md item 20): if a
     * later upload replaced it, the replacement is the one to ask about.
     */
    private static boolean isCurrentVerifiedLicense(PersonalDocument document) {
        return (
            PersonalDocumentService.isLive(document) &&
            document.getType() == DocumentType.LICENSE &&
            document.getVerificationStatus() == VerificationStatus.VERIFIED &&
            document.getExpiryDate() != null &&
            !document.getExpiryDate().isBefore(java.time.LocalDate.now())
        );
    }

    /**
     * Profile-scoped form of {@link #isCurrentVerifiedLicense}, for the compliance sweep.
     *
     * <p>Profile-scoped rather than application-scoped because the sweep starts from an expired
     * document and already holds its profile id; taking an application would only make it re-derive
     * the same value. Null-tolerant for the same reason — a document may name no profile.
     */
    public boolean hasCurrentVerifiedLicense(String profileId) {
        return (
            profileId != null &&
            personalDocumentRepository.findByProfileId(profileId).stream().anyMatch(OnboardingService::isCurrentVerifiedLicense)
        );
    }

    public List<ProfessionalApplication> listApplications(ProfileStatus status) {
        if (status == null) {
            return applicationRepository.findAll(
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "submittedAt")
            );
        }
        return applicationRepository.findByStatusOrderBySubmittedAtDesc(status);
    }

    /**
     * Reviewer access to an application's documents; bytes stripped by the resource layer.
     *
     * <p><b>Archived rows included, and that is the point of not deleting them</b> (backlog.md item
     * 20): a superseded licence is evidence of what a clinician held while they were treating
     * patients, so the credential history stays readable here for ever. Each row carries
     * {@code supersededAt} and {@code supersededByDocumentId}, so the reviewer screen can label an
     * archived row and keep it out of its own "every document verified" check — which is the client
     * mirror of {@link #requireAllMandatoryDocumentsVerified}, and would otherwise let an archived
     * row block approval from the browser after the server had stopped letting it.
     */
    public List<PersonalDocument> documentsForApplication(String applicationId) {
        return documentsFor(getById(applicationId));
    }

    public PersonalDocument verifyDocument(String documentId, String actor) {
        PersonalDocument document = requireDocument(documentId);
        document.verificationStatus(VerificationStatus.VERIFIED).verifiedBy(actor).verifiedAt(Instant.now()).rejectionReason(null);
        // isVerified moves here without the Profile row being touched; the save of the document is
        // what ProfileStatusAnnouncer listens for, so the far side learns it without this method
        // saying so.
        return personalDocumentRepository.save(document);
    }

    public PersonalDocument rejectDocument(String documentId, String reason, String actor) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rejecting a document requires a reason");
        }
        PersonalDocument document = requireDocument(documentId);
        document.verificationStatus(VerificationStatus.REJECTED).verifiedBy(actor).verifiedAt(Instant.now()).rejectionReason(reason);
        // The mirror of verifyDocument: a rejection takes isVerified back to false, and a directory
        // left showing a clinician as verified after one is the worse half of the two. Announced by
        // the save, like every other write that moves it — an upload does the same thing and used to
        // announce nothing at all (backlog.md item 49).
        return personalDocumentRepository.save(document);
    }

    private PersonalDocument requireDocument(String documentId) {
        return personalDocumentRepository
            .findById(documentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
    }

    /** Reason marker for the step-11 first-login acknowledgement event. */
    public static final String ACKNOWLEDGEMENT_REASON = "first-login-acknowledgement";

    /** First-login orientation (workflow step 11): recorded as an OnboardingEvent, idempotent. */
    public OnboardingEvent acknowledgeFirstLogin(String accountId) {
        ProfessionalApplication application = getOwnApplication(accountId);
        boolean already = eventRepository
            .findByApplicationIdOrderByAtAsc(application.getId())
            .stream()
            .anyMatch(event -> ACKNOWLEDGEMENT_REASON.equals(event.getReason()));
        if (already) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already acknowledged");
        }
        return eventRepository.save(
            new OnboardingEvent()
                .applicationId(application.getId())
                .actor(accountId)
                .fromStatus(application.getStatus())
                .toStatus(application.getStatus())
                .reason(ACKNOWLEDGEMENT_REASON)
                .at(Instant.now())
        );
    }

    public boolean hasAcknowledgedFirstLogin(String accountId) {
        return applicationRepository
            .findByAccountId(accountId)
            .map(
                application ->
                    eventRepository
                        .findByApplicationIdOrderByAtAsc(application.getId())
                        .stream()
                        .anyMatch(event -> ACKNOWLEDGEMENT_REASON.equals(event.getReason()))
            )
            .orElse(true); // no application -> nothing to acknowledge
    }

    public List<OnboardingEvent> eventsFor(String applicationId) {
        return eventRepository.findByApplicationIdOrderByAtAsc(applicationId);
    }

    public ProfessionalApplication getById(String applicationId) {
        return applicationRepository
            .findById(applicationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found"));
    }

    private ProfessionalApplication transition(ProfessionalApplication application, ProfileStatus to, String actor, String reason) {
        ProfileStatus from = application.getStatus();
        if (from == null || !LEGAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Illegal onboarding transition " + from + " -> " + to);
        }
        application.status(to);
        ProfessionalApplication saved = applicationRepository.save(application);
        appendEvent(saved, from, to, reason);
        return saved;
    }

    private void appendEvent(ProfessionalApplication application, ProfileStatus from, ProfileStatus to, String reason) {
        eventRepository.save(
            new OnboardingEvent()
                .applicationId(application.getId())
                .actor(net.jojoaddison.security.SecurityUtils.getCurrentUserLogin().orElse("system"))
                .fromStatus(from)
                .toStatus(to)
                .reason(reason)
                .at(Instant.now())
        );
    }

    /**
     * The eight requirements, equally weighted, in the order the profile page shows them.
     *
     * <p>Named constants rather than inline strings because the client maps every key to a
     * translated label in four languages: a key renamed here and not there renders as the key
     * itself, mid-screen, with nothing thrown and nothing logged.
     */
    private static final String REQ_CONSENT = "consent";

    /**
     * The three step-2 keys, <b>taken from {@link ProfileCompleteness} rather than spelled again</b>
     * (F-B). They were private literals here until the submit gate started naming the same keys; one
     * declaration is what stops the meter and the refusal disagreeing about a translated label.
     */
    private static final String REQ_PROFILE = ProfileCompleteness.REQ_PROFILE;

    private static final String REQ_ADDRESS = ProfileCompleteness.REQ_ADDRESS;

    private static final String REQ_NEXT_OF_KIN = ProfileCompleteness.REQ_NEXT_OF_KIN;

    private static final String REQ_CERTIFICATE = "certificate";
    private static final String REQ_LICENSE = "license";
    private static final String REQ_IDENTITY = "identity";
    private static final String REQ_PHOTO = "photo";

    /**
     * Step 4's other half: the role the applicant declares they are applying for.
     *
     * <p>Not one of the eight progress-meter requirements, and deliberately so — the meter measures
     * what the <em>profile</em> holds, and the authority is a property of the application. It is a
     * submission requirement all the same: {@code profile.md} step 4 is <i>"The professional declares
     * the role they are applying for <b>and</b> consents"</i>, so a submission naming no role has not
     * satisfied it. Nothing refused one before F-B.
     */
    private static final String REQ_AUTHORITY = "authority";

    /**
     * How far this account has got, for its own eyes.
     *
     * <p>Answers for an account with no application at all — everything false, 0% — rather than
     * 404ing, because that is the state every clinician created by admin invitation starts in and
     * the profile page has to render something for them.
     */
    public OnboardingProgressDTO progressFor(String accountId) {
        ProfessionalApplication application = applicationRepository.findByAccountId(accountId).orElse(null);
        Profile profile = profileRepository.findByAccountId(accountId).orElse(null);
        // Resolved from the profile, not via documentsFor(application): that throws 409 when the
        // application has no linked profile yet, which is precisely one of the incomplete states
        // this method exists to report on.
        //
        // Live rows only: a requirement is about what the professional holds now, and an archived row
        // must be able neither to satisfy one nor to fail one (backlog.md item 20).
        List<PersonalDocument> documents = profile == null || profile.getId() == null
            ? List.<PersonalDocument>of()
            : personalDocumentRepository.findByProfileId(profile.getId()).stream().filter(PersonalDocumentService::isLive).toList();

        List<OnboardingProgressDTO.Requirement> requirements = List.of(
            new OnboardingProgressDTO.Requirement(REQ_CONSENT, application != null && application.isAgreed()),
            new OnboardingProgressDTO.Requirement(REQ_PROFILE, personalDetailsComplete(profile)),
            new OnboardingProgressDTO.Requirement(REQ_ADDRESS, addressComplete(profile)),
            new OnboardingProgressDTO.Requirement(REQ_NEXT_OF_KIN, nextOfKinComplete(profile)),
            new OnboardingProgressDTO.Requirement(
                REQ_CERTIFICATE,
                documents.stream().anyMatch(d -> d.getType() == DocumentType.CERTIFICATE)
            ),
            new OnboardingProgressDTO.Requirement(
                REQ_LICENSE,
                documents.stream().anyMatch(d -> d.getType() == DocumentType.LICENSE && d.getExpiryDate() != null)
            ),
            new OnboardingProgressDTO.Requirement(REQ_IDENTITY, documents.stream().anyMatch(d -> IDENTITY_TYPES.contains(d.getType()))),
            new OnboardingProgressDTO.Requirement(REQ_PHOTO, documents.stream().anyMatch(d -> d.getType() == DocumentType.PASSPHOTO))
        );

        long done = requirements.stream().filter(OnboardingProgressDTO.Requirement::done).count();
        int percent = Math.toIntExact(Math.round(((double) done / requirements.size()) * 100));
        return new OnboardingProgressDTO(
            percent,
            done == requirements.size(),
            application == null ? null : application.getStatus(),
            requirements
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * ⛔ <b>An {@code authority} that is not one of the eight professional disciplines is refused with
     * 400, on every path that writes one</b> — the create, Save and Submit.
     *
     * <p>Nothing validated it before: {@code {"agreed":true,"authority":"banana"}} stored and answered
     * 201, after which the review queue rendered {@code healthConnect.roles.banana} — the raw
     * translation key, mid-screen, in all four locales — and the review-detail page handed the value
     * to the gateway's {@code grantAuthority}. ⚠ The field a reviewer acts on is the one an applicant
     * writes, which is why this is a write gate and not a rendering fix.
     *
     * <h2>⭐ The valid set is derived, not listed here</h2>
     *
     * <p>{@link AuthoritiesConstants#PROFESSIONAL_DISCIPLINES} is {@code CLINICAL_AND_ADMIN} minus the
     * administrator, so a ninth discipline is accepted the day it is added to this service's own copy
     * of the authorities and nobody edits this method. {@code ROLE_ADMIN} and {@code ROLE_USER} are
     * outside it by that construction rather than by a clause — see that constant.
     *
     * <h2>⚠ Absent is not invalid, and this method is where the two are kept apart</h2>
     *
     * <p>A write naming <em>no</em> authority is not refused here: the create stores {@code null} and
     * answers 201, Save <b>leaves the stored value untouched</b> and answers 200 (owner decision
     * 2026-10-09 — see {@link #saveConsent}), and Submit by an applicant who has never declared one
     * refuses through {@link #unsatisfiedRequirements} with {@code authority} among the missing keys.
     * That matters
     * beyond tidiness — {@code careers-handoff-contract.md} has the client <b>drop</b> an unknown
     * {@code ?track=} rather than raise, <i>"and the page still works with no parameters at all"</i>,
     * so a body that names nothing is the contract working and a body that names {@code "banana"} is
     * the backstop firing. <b>Blank counts as absent</b>, by {@link #hasText} — the same definition of
     * absence the submission gate already uses, rather than a second one that would make {@code ""} a
     * 400 on Save where it is currently a 200.
     *
     * <h2>⭐ Reads are deliberately not validated</h2>
     *
     * <p>A filter is not a write. The two places an authority arrives as a <em>query</em> are
     * {@code MessagingResource}'s recipient picker ({@code ?role=}) and its role broadcast, both
     * reaching {@code ProfessionalApplicationRepository.findByAuthorityAndStatus}; a non-member there
     * matches nothing and <b>answers empty</b>, which is the truthful answer — no active professional
     * holds it — and the broadcast separately refuses an empty match with its own 400. Refusing the
     * read instead would make a filter able to fail on data the server itself stored before this gate
     * existed, and the quality database holds exactly such a row. ⚠ <b>The review queue does not
     * filter by authority at all</b> — {@code GET /api/professional-application} takes
     * {@code ?status=} and nothing else — so there is no admin read to decide about.
     */
    private static void refuseAnAuthorityThatIsNotADiscipline(String authority) {
        if (!hasText(authority)) {
            return;
        }
        if (!List.of(AuthoritiesConstants.PROFESSIONAL_DISCIPLINES).contains(authority)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                AUTHORITY_MUST_BE_A_PROFESSIONAL_DISCIPLINE + ": " + String.join(", ", AuthoritiesConstants.PROFESSIONAL_DISCIPLINES)
            );
        }
    }

    private static boolean personalDetailsComplete(Profile profile) {
        return (
            profile != null &&
            hasText(profile.getFirstName()) &&
            hasText(profile.getLastName()) &&
            profile.getBirthDate() != null &&
            // `sex` and `cardType` are enums since F9 — a null check is the whole of "provided"
                // now, where hasText used to be the only thing a free-text field admitted. A value
                // outside the enumeration can no longer reach storage, so this predicate no longer
                // counts "banana" as a sex.
                profile.getSex() !=
                null &&
            hasText(profile.getMobilePhone()) &&
            profile.getCardType() != null &&
            hasText(profile.getCardNumber())
        );
    }

    /** Mirrors the wizard's required address fields; town, district and digital address stay optional. */
    private static boolean addressComplete(Profile profile) {
        return (
            profile != null &&
            profile.getAddress() != null &&
            hasText(profile.getAddress().getStreetAddress()) &&
            hasText(profile.getAddress().getCity()) &&
            hasText(profile.getAddress().getRegion()) &&
            hasText(profile.getAddress().getCountry())
        );
    }

    /**
     * What "next of kin provided" means: <b>at least {@link ProfileCompleteness#REQUIRED_CONTACTS}
     * contacts</b>, each carrying name, relationship and phone (F2).
     *
     * <p>{@code profile.md} step 2: <i>"At least <b>two</b> emergency contacts are required."</i>
     * This was an {@code anyMatch}, so one contact satisfied it, and <b>nothing anywhere in
     * {@code src/main} or {@code src/test} expressed the requirement</b> — the only mention of it in
     * the repository was a comment in {@code ProfilePatchFieldCoverageIT}.
     *
     * <p>⛔ <b>The javadoc that stood here argued for one and deferred two to T5. It was overruled
     * and is deleted rather than reworded.</b> Its argument was that raising the predicate would
     * make profiles already saved through the shipped one-contact wizard retroactively incomplete.
     * That is true, it is the accepted consequence, and <i>"this would make existing rows
     * retroactively incomplete"</i> is not a reason to deviate from the specification. The three
     * things it named as blast radius are the right things to look at and none of them is a reason
     * either.
     *
     * <p><b>Counting the complete ones rather than requiring every contact complete</b>, which is
     * the one clause of the old reasoning that survives on its own merits: a clinician with two good
     * contacts who starts typing a third would otherwise see this requirement go from satisfied to
     * unsatisfied, and the {@code ACTIVE} gate close, for having done what the form invites. ⚠ That
     * makes this predicate deliberately <em>weaker</em> than {@link ProfileCompleteness}, which
     * requires every contact complete because it decides a status rather than a meter — see that
     * class on which definition is authoritative for what.
     *
     * <p><b>Advisory and server-side, as it was.</b> {@code Profile} carries no
     * {@code jakarta.validation} annotations and gains none here: what the server owns is whether a
     * <em>requirement</em> is satisfied, which is this.
     */
    private static boolean nextOfKinComplete(Profile profile) {
        return (
            profile != null &&
            profile.getContacts() != null &&
            profile
                    .getContacts()
                    .stream()
                    .filter(
                        contact ->
                            contact != null &&
                            hasText(contact.getName()) &&
                            hasText(contact.getRelationship()) &&
                            hasText(contact.getPhone())
                    )
                    .count() >=
                ProfileCompleteness.REQUIRED_CONTACTS
        );
    }

    /**
     * ⛔ <b>Every requirement this service can see, named when it is not satisfied</b> —
     * {@code profile.md} § Gap Update: <i>"Submitting sets {@code Application.status} to
     * {@code CREDENTIAL_REVIEW} and triggers the Kafka event, <b>when all requirements are
     * satisfied</b>."</i> (F-B)
     *
     * <table>
     *   <caption>What is gated, and by which definition</caption>
     *   <tr><th>step</th><th>requirement</th><th>read from</th></tr>
     *   <tr><td>1</td><td>the four account fields</td>
     *       <td><b>not gated</b> — they are {@code User}'s, in the gateway; see
     *           {@link #submitForReview}</td></tr>
     *   <tr><td>2</td><td>{@code profile}, {@code address}, {@code nextOfKin}</td>
     *       <td>{@link ProfileCompleteness#missingRequirements} — the predicate built for
     *           {@code profile.md}'s <i>"Every field in the Profile model is required"</i></td></tr>
     *   <tr><td>3</td><td>{@code certificate}, {@code license}, {@code identity}, {@code photo}</td>
     *       <td>the live documents on the caller's profile, as before</td></tr>
     *   <tr><td>4</td><td>{@code authority}</td>
     *       <td>the role on the submitted body; consent is refused earlier and separately, with
     *           {@link #CONSENT_REQUIRED}, because a withheld tick is a different answer from an
     *           incomplete one</td></tr>
     * </table>
     *
     * <p>⭐ <b>Step 2's definition is {@link ProfileCompleteness}, not the progress meter's three
     * predicates.</b> Those are deliberately weaker — {@code addressComplete} leaves
     * {@code digitalAddress}, {@code town} and {@code district} optional, and
     * {@code nextOfKinComplete} counts complete contacts rather than requiring every contact
     * complete, so that a meter does not go <em>down</em> when a clinician starts typing a third
     * contact. A <em>gate</em> has no such problem and {@code profile.md} says every field, so the
     * gate reads the stricter one. That also closes the gap {@code ProfileCompleteness}'s own javadoc
     * records: a profile could read 100% on the meter and carry no {@code status}.
     *
     * <p><b>400 on Submit, not 409.</b> Every one of these is something the applicant can fix and
     * then retry, which is what distinguishes it from {@link #markStatus}'s {@code ACTIVE} gate —
     * that one refuses an <em>administrator</em> acting on a state only the clinician can change, and
     * answers {@code CONFLICT}. On Save a non-empty answer is not an error at all; see
     * {@link #saveConsent}.
     *
     * <p>⚠ <b>Documents are resolved from the profile, not via {@code documentsFor(application)}</b>,
     * which raises 409 <i>"Application has no linked profile"</i> for an application with no
     * {@code profileId} — precisely one of the incomplete states this is asked about. That is the
     * same reasoning {@link #progressFor} records for the same choice; before F-B an applicant with
     * no profile at all met that 409 instead of being told what was missing.
     *
     * <p>⭐ <b>It returns the keys and refuses nothing</b> (owner decision 2026-10-09). Both of
     * step 4's buttons ask the same question and only one of them turns a non-empty answer into a
     * 400 — so the evaluation cannot live inside the refusal, or Save would have to re-derive it.
     *
     * @param authority ⚠ the authority <b>as stored on the application after this write</b>, not as
     *     the body named it. Since the owner's decision of 2026-10-09 a body naming none no longer
     *     blanks the field, so the two differ — and keying step 4's requirement on the body would name
     *     {@code authority} missing on an application that carries one. See
     *     {@code storeThenAdvanceWhenComplete}, the only caller.
     * @return the unsatisfied requirement keys, in the order the profile page shows them; empty when
     *     every requirement this service can see is satisfied.
     */
    private List<String> unsatisfiedRequirements(String accountId, String authority) {
        Profile profile = profileRepository.findByAccountId(accountId).orElse(null);
        List<String> missing = new java.util.ArrayList<>(ProfileCompleteness.missingRequirements(profile));

        List<PersonalDocument> documents = profile == null || profile.getId() == null
            ? List.<PersonalDocument>of()
            : personalDocumentRepository.findByProfileId(profile.getId()).stream().filter(PersonalDocumentService::isLive).toList();
        if (documents.stream().noneMatch(d -> d.getType() == DocumentType.CERTIFICATE)) {
            missing.add(REQ_CERTIFICATE);
        }
        if (documents.stream().noneMatch(d -> d.getType() == DocumentType.LICENSE && d.getExpiryDate() != null)) {
            missing.add(REQ_LICENSE);
        }
        if (documents.stream().noneMatch(d -> IDENTITY_TYPES.contains(d.getType()))) {
            missing.add(REQ_IDENTITY);
        }
        if (documents.stream().noneMatch(d -> d.getType() == DocumentType.PASSPHOTO)) {
            missing.add(REQ_PHOTO);
        }

        if (!hasText(authority)) {
            missing.add(REQ_AUTHORITY);
        }

        return List.copyOf(missing);
    }

    /**
     * Approval requires every document the applicant currently offers to be verified.
     *
     * <p><b>Currently</b> is load-bearing (backlog.md item 20). Against the unfiltered list an
     * archived row could fail approval for ever: a document rejected by a reviewer, re-uploaded, and
     * verified would leave the original REJECTED row in the collection, and {@code allMatch} would
     * refuse the application on the strength of a document that had already been replaced — with no
     * action open to anyone that would clear it, since the row is deliberately never deleted.
     */
    private void requireAllMandatoryDocumentsVerified(ProfessionalApplication application) {
        // documentsFor is still called first, for its refusal: it raises 409 "Application has no
        // linked profile" for an application with no profileId, which is a different and more useful
        // answer than the `false` the profile-scoped predicate would give for a null id.
        documentsFor(application);
        if (!allLiveDocumentsVerified(application.getProfileId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval requires every uploaded document to be verified");
        }
    }

    /** Every document on the application's profile, archived rows included — the reviewer's history view. */
    private List<PersonalDocument> documentsFor(ProfessionalApplication application) {
        String profileId = application.getProfileId();
        if (profileId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Application has no linked profile");
        }
        return personalDocumentRepository.findByProfileId(profileId);
    }
    /*
     * liveDocumentsFor(ProfessionalApplication) is GONE — its one caller was requireMandatoryDocuments,
     * which F-B folded into requireEverySubmissionRequirement. That gate resolves documents from the
     * PROFILE rather than from the application, deliberately: documentsFor() raises 409 "Application has
     * no linked profile" for an application with no profileId, which is one of the very states the gate
     * exists to report on. Deleted rather than left behind, because a private helper with no caller is
     * the next reader's evidence that this path still goes through the application.
     */
}
