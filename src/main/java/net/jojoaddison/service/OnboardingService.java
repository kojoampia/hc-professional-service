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
     * @param accountId the caller's gateway {@code User.id} — the key this application is found by.
     * @param login the caller's login, stored beside it as the human-readable name. Until item 50
     *     both fields were written from one value, because both <em>were</em> the login; they are
     *     now two identifiers and the caller passes each explicitly.
     */
    public ProfessionalApplication startApplication(
        String accountId,
        String login,
        String requestedRole,
        boolean consentAccepted,
        String invitedBy,
        String source
    ) {
        if (!consentAccepted) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Consent must be accepted to start an application");
        }
        applicationRepository
            .findByAccountId(accountId)
            .ifPresent(existing -> {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An application already exists for this account");
            });
        ProfessionalApplication application = applicationRepository.save(
            new ProfessionalApplication()
                .accountId(accountId)
                .login(login)
                .requestedRole(requestedRole)
                .status(ProfileStatus.APPLICATION_STARTED)
                .consentAcceptedAt(Instant.now())
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

    /**
     * Applicant profile upsert (WP4 support): applicants hold only ROLE_USER,
     * which the WP1 mutation matrix blocks from POST /api/profiles — their
     * profile is written through the onboarding surface instead. accountId is
     * always forced to the caller; an existing profile keeps its id.
     *
     * <p><b>This is the write that carries the second half of a clinician's arrival</b>
     * ({@code ProfileStatus}, backlog.md item 47). It no longer announces it: the save does, through
     * {@link ProfileStatusAnnouncer}, which listens for the persisted document rather than being
     * called from a table of paths. This method was one of the four entries on that table and item 49
     * is what the table missed — see the announcer's javadoc.
     *
     * <h2>⚠ Superseded by {@code PUT /api/profile}, and kept until T3 retires the path</h2>
     *
     * <p>{@code profile.md} replaces this endpoint with {@code PUT /api/profile}
     * ({@code OwnProfileResource}), and <b>the unconditional write below is the specific behaviour
     * that was replaced rather than carried over</b>: there is no {@code != null} guard on any of
     * these, so a client that sends one pane's fields blanks every field the other panes wrote.
     * Today's clients only survive it by sending the whole document back —
     * {@code web/}'s {@code clinical-profile.component.ts} spreads <code>{...this.loaded, …}</code>
     * and {@code mobile/}'s {@code me.page.ts} spreads the loaded contact — which makes correctness a
     * property of the caller. {@code ProfileService.partialUpdateOwnProfile} is the guarded version.
     *
     * <p>⛔ <b>Do not "fix" this method to be partial.</b> Two write paths that disagree about what a
     * missing field means is worse than one that is wrong in a documented way, and the clients above
     * are written against this behaviour. It goes away with the rest of {@code /api/onboarding/**}.
     *
     * <p><b>{@code middleNames} is copied as of profile.md's T1, and it never was before.</b> The
     * field has been on {@code Profile} and in {@code .jhipster/Profile.json} since WP2 and no write
     * path anywhere copied it, so the middle name the wizard collects was stored by nothing while
     * {@code profile.md}'s header renders "firstName middleName lastName". Added here as well as on
     * the new path because this is still the live writer until T6.
     */
    public Profile upsertOwnProfile(String accountId, Profile incoming) {
        Profile profile = profileRepository.findByAccountId(accountId).orElse(null);
        boolean created = profile == null;
        if (created) {
            profile = new Profile();
        }
        // accountUid used to be stamped here, beside a login-valued accountId. Item 50 removed the
        // field: accountId now holds the very value accountUid held, so keeping both would be the
        // second join key that item exists to remove, spelled twice in one document.
        profile
            .accountId(accountId)
            .firstName(incoming.getFirstName())
            .lastName(incoming.getLastName())
            .birthDate(incoming.getBirthDate())
            .sex(incoming.getSex())
            .mobilePhone(incoming.getMobilePhone())
            .phoneNumber(incoming.getPhoneNumber())
            .email(incoming.getEmail())
            .cardType(incoming.getCardType())
            .cardNumber(incoming.getCardNumber())
            .title(incoming.getTitle())
            .address(incoming.getAddress())
            .middleNames(incoming.getMiddleNames())
            .contacts(incoming.getContacts());
        Profile saved = profileRepository.save(profile);
        if (created) {
            domainEventPublisher.publishEntityCreated(
                "Profile",
                saved.getId(),
                saved.getAccountId(),
                net.jojoaddison.security.SecurityUtils.getCurrentUserLogin().orElse("system")
            );
        }
        return saved;
    }

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

    public Profile getOwnProfile(String accountId) {
        return profileRepository
            .findByAccountId(accountId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No profile for this account yet"));
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

    public ProfessionalApplication submitForReview(String accountId) {
        ProfessionalApplication application = getOwnApplication(accountId);
        requireMandatoryDocuments(application);
        application.submittedAt(Instant.now());
        ProfessionalApplication saved = transition(
            application,
            ProfileStatus.CREDENTIAL_REVIEW,
            accountId,
            "submitted for credential review"
        );
        // COMPLETED means the applicant is done, not that they are cleared to work — the ACTIVE
        // event below says that. Keeping them apart is what lets the admin portal tell an
        // application stalled on us from one stalled on the clinician.
        domainEventPublisher.publishOnboardingState("COMPLETED", accountId, saved.getId(), saved.getRequestedRole(), accountId);
        return saved;
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
            domainEventPublisher.publishOnboardingState("ACTIVE", saved.getAccountId(), saved.getId(), saved.getRequestedRole(), actor);
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
    private static final String REQ_PROFILE = "profile";
    private static final String REQ_ADDRESS = "address";
    private static final String REQ_NEXT_OF_KIN = "nextOfKin";
    private static final String REQ_CERTIFICATE = "certificate";
    private static final String REQ_LICENSE = "license";
    private static final String REQ_IDENTITY = "identity";
    private static final String REQ_PHOTO = "photo";

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
            new OnboardingProgressDTO.Requirement(REQ_CONSENT, application != null && application.getConsentAcceptedAt() != null),
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

    private static boolean personalDetailsComplete(Profile profile) {
        return (
            profile != null &&
            hasText(profile.getFirstName()) &&
            hasText(profile.getLastName()) &&
            profile.getBirthDate() != null &&
            hasText(profile.getSex()) &&
            hasText(profile.getMobilePhone()) &&
            hasText(profile.getCardType()) &&
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
     * What "next of kin provided" means now that {@code Profile.contacts} is a list (profile.md, T1).
     *
     * <h2>The decision: at least ONE contact carrying name, relationship and phone</h2>
     *
     * <p>Not "every contact complete", and <b>not two</b>, and both exclusions are deliberate.
     *
     * <p><em>Every contact complete</em> turns adding a second contact into a way to go backwards: a
     * clinician with one good contact who starts typing a second would see their meter drop and their
     * activation gate close, which punishes exactly the behaviour the specification asks for.
     *
     * <p><em>Two contacts</em> is what {@code profile.md} step 2 requires of the <b>form</b>, and it
     * is deliberately not asserted here yet. Raising this predicate to two would make every profile
     * already saved through the shipped one-contact wizard retroactively incomplete — the meter drops
     * for clinicians who did nothing, and the {@code ACTIVE} gate refuses activation for applicants
     * mid-review. That is a change to the progress model with its own blast radius
     * ({@code OnboardingProgressIT} states the arithmetic, four i18n catalogues carry the labels,
     * {@code CompleteOnboardingFixture} is the one fixture, and the post-sign-in redirect reads the
     * result), and {@code profile-addendum.md} assigns it to <b>T5</b>, where the four-step meter is
     * built. Doing it here would be the same change arriving without the rest of its own task.
     *
     * <p><b>This is therefore behaviour-preserving by construction:</b> for a profile holding exactly
     * one contact — every profile in the database before the migration — it answers precisely what
     * the singular version answered.
     *
     * <p><b>Advisory and server-side, as it was.</b> {@code Profile} carries no
     * {@code jakarta.validation} annotations and gains none here: "every field is required" is a
     * client-side rule, and making the server enforce it would reject rows the current client
     * legitimately saves. What the server owns is whether a <em>requirement</em> is satisfied, which
     * is this.
     */
    private static boolean nextOfKinComplete(Profile profile) {
        return (
            profile != null &&
            profile.getContacts() != null &&
            profile
                .getContacts()
                .stream()
                .anyMatch(
                    contact ->
                        contact != null && hasText(contact.getName()) && hasText(contact.getRelationship()) && hasText(contact.getPhone())
                )
        );
    }

    private void requireMandatoryDocuments(ProfessionalApplication application) {
        List<PersonalDocument> documents = liveDocumentsFor(application);
        boolean hasCertificate = documents.stream().anyMatch(d -> d.getType() == DocumentType.CERTIFICATE);
        boolean hasLicenseWithExpiry = documents.stream().anyMatch(d -> d.getType() == DocumentType.LICENSE && d.getExpiryDate() != null);
        boolean hasIdentity = documents.stream().anyMatch(d -> IDENTITY_TYPES.contains(d.getType()));
        boolean hasPhoto = documents.stream().anyMatch(d -> d.getType() == DocumentType.PASSPHOTO);
        if (!hasCertificate || !hasLicenseWithExpiry || !hasIdentity || !hasPhoto) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Mandatory documents missing: certificate, license (with expiry), government identity, and passport photo are required"
            );
        }
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

    /** What the professional holds now — the form every gate reads. See {@link PersonalDocumentService#isLive}. */
    private List<PersonalDocument> liveDocumentsFor(ProfessionalApplication application) {
        return documentsFor(application).stream().filter(PersonalDocumentService::isLive).toList();
    }
}
