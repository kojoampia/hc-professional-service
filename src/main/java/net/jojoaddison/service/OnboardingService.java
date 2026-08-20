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
import net.jojoaddison.domain.enumeration.OnboardingStatus;
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
 * with 409 CONFLICT. Account linkage note: the JWT exposes only the login
 * (subject), so {@code accountId} carries the gateway login for now — switch
 * to User.id once the gateway adds a uid claim.
 */
@Service
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    private static final Set<DocumentType> IDENTITY_TYPES = EnumSet.of(
        DocumentType.PASSPORT,
        DocumentType.GHANACARD,
        DocumentType.DRIVERLICENSE,
        DocumentType.VOTERCARD
    );

    private static final Map<OnboardingStatus, Set<OnboardingStatus>> LEGAL_TRANSITIONS = Map.ofEntries(
        Map.entry(OnboardingStatus.APPLICATION_STARTED, EnumSet.of(OnboardingStatus.PROFILE_COMPLETED)),
        Map.entry(OnboardingStatus.PROFILE_COMPLETED, EnumSet.of(OnboardingStatus.CREDENTIAL_REVIEW)),
        Map.entry(
            OnboardingStatus.CREDENTIAL_REVIEW,
            EnumSet.of(OnboardingStatus.APPROVED, OnboardingStatus.REJECTED, OnboardingStatus.RETURNED_FOR_CORRECTION)
        ),
        Map.entry(
            OnboardingStatus.RETURNED_FOR_CORRECTION,
            EnumSet.of(OnboardingStatus.PROFILE_COMPLETED, OnboardingStatus.CREDENTIAL_REVIEW)
        ),
        Map.entry(
            OnboardingStatus.APPROVED,
            EnumSet.of(
                OnboardingStatus.ORGANIZATION_ASSIGNED,
                OnboardingStatus.SUSPENDED,
                OnboardingStatus.EXPIRED,
                OnboardingStatus.DEACTIVATED
            )
        ),
        Map.entry(
            OnboardingStatus.ORGANIZATION_ASSIGNED,
            EnumSet.of(
                OnboardingStatus.AUTHORITY_ASSIGNED,
                OnboardingStatus.SUSPENDED,
                OnboardingStatus.EXPIRED,
                OnboardingStatus.DEACTIVATED
            )
        ),
        Map.entry(
            OnboardingStatus.AUTHORITY_ASSIGNED,
            EnumSet.of(
                OnboardingStatus.ROSTER_CONFIGURED,
                OnboardingStatus.SUSPENDED,
                OnboardingStatus.EXPIRED,
                OnboardingStatus.DEACTIVATED
            )
        ),
        Map.entry(
            OnboardingStatus.ROSTER_CONFIGURED,
            EnumSet.of(OnboardingStatus.ACTIVE, OnboardingStatus.SUSPENDED, OnboardingStatus.EXPIRED, OnboardingStatus.DEACTIVATED)
        ),
        Map.entry(OnboardingStatus.ACTIVE, EnumSet.of(OnboardingStatus.SUSPENDED, OnboardingStatus.EXPIRED, OnboardingStatus.DEACTIVATED)),
        Map.entry(OnboardingStatus.SUSPENDED, EnumSet.of(OnboardingStatus.ACTIVE, OnboardingStatus.EXPIRED, OnboardingStatus.DEACTIVATED)),
        Map.entry(OnboardingStatus.EXPIRED, EnumSet.of(OnboardingStatus.CREDENTIAL_REVIEW, OnboardingStatus.DEACTIVATED)),
        Map.entry(OnboardingStatus.REJECTED, EnumSet.noneOf(OnboardingStatus.class)),
        Map.entry(OnboardingStatus.DEACTIVATED, EnumSet.noneOf(OnboardingStatus.class))
    );

    private final ProfessionalApplicationRepository applicationRepository;
    private final OnboardingEventRepository eventRepository;
    private final ProfileRepository profileRepository;
    private final PersonalDocumentRepository personalDocumentRepository;
    private final DomainEventPublisher domainEventPublisher;

    public OnboardingService(
        ProfessionalApplicationRepository applicationRepository,
        OnboardingEventRepository eventRepository,
        ProfileRepository profileRepository,
        PersonalDocumentRepository personalDocumentRepository,
        DomainEventPublisher domainEventPublisher
    ) {
        this.applicationRepository = applicationRepository;
        this.eventRepository = eventRepository;
        this.profileRepository = profileRepository;
        this.personalDocumentRepository = personalDocumentRepository;
        this.domainEventPublisher = domainEventPublisher;
    }

    public ProfessionalApplication startApplication(
        String accountId,
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
                .login(accountId)
                .requestedRole(requestedRole)
                .status(OnboardingStatus.APPLICATION_STARTED)
                .consentAcceptedAt(Instant.now())
                .invitedBy(invitedBy)
                .source(normalizeSource(source))
        );
        appendEvent(application, null, OnboardingStatus.APPLICATION_STARTED, "application started");
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
     */
    public Profile upsertOwnProfile(String accountId, Profile incoming) {
        Profile profile = profileRepository.findByAccountId(accountId).orElse(null);
        boolean created = profile == null;
        if (created) {
            profile = new Profile();
        }
        profile
            .accountId(accountId)
            .firstName(incoming.getFirstName())
            .middleNames(incoming.getMiddleNames())
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
            .emergencyContact(incoming.getEmergencyContact());
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
        return transition(application, OnboardingStatus.PROFILE_COMPLETED, accountId, "profile completed");
    }

    public ProfessionalApplication submitForReview(String accountId) {
        ProfessionalApplication application = getOwnApplication(accountId);
        requireMandatoryDocuments(application);
        application.submittedAt(Instant.now());
        ProfessionalApplication saved = transition(
            application,
            OnboardingStatus.CREDENTIAL_REVIEW,
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
        OnboardingStatus decision,
        String reason,
        String correctionNotes,
        String actor
    ) {
        if (
            decision != OnboardingStatus.APPROVED &&
            decision != OnboardingStatus.REJECTED &&
            decision != OnboardingStatus.RETURNED_FOR_CORRECTION
        ) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Decision must be APPROVED, REJECTED or RETURNED_FOR_CORRECTION");
        }
        if (decision != OnboardingStatus.APPROVED && (reason == null || reason.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rejection or correction requires a reviewer reason");
        }
        ProfessionalApplication application = getById(applicationId);
        if (decision == OnboardingStatus.APPROVED) {
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
        profile.specialtyCategoryId(specialtyCategoryId);
        if (teamIds != null) {
            profile.teamIds(teamIds);
        }
        profileRepository.save(profile);
        log.debug("Organization context assigned to profile {} (supervisor {})", profile.getId(), supervisorProfileId);
        return transition(application, OnboardingStatus.ORGANIZATION_ASSIGNED, actor, "organization context assigned");
    }

    public ProfessionalApplication markStatus(String applicationId, OnboardingStatus target, String reason, String actor) {
        ProfessionalApplication application = getById(applicationId);
        if (target == OnboardingStatus.ACTIVE) {
            // A profile goes ACTIVE only when it is complete AND vetted. The vetting half is the
            // APPROVED -> ... -> ACTIVE chain, which only an admin can drive; this is the other
            // half, and it is checked here rather than in the client because an admin activating an
            // incomplete application is a bug, not a shortcut.
            requireCompleteProfile(application);
            // WP7 reactivation guard: leaving SUSPENDED additionally requires a current license.
            if (application.getStatus() == OnboardingStatus.SUSPENDED) {
                requireCurrentVerifiedLicense(application);
            }
        }
        ProfessionalApplication saved = transition(application, target, actor, reason);
        if (target == OnboardingStatus.ACTIVE) {
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Activation requires a complete profile; still missing: " + missing);
        }
    }

    private void requireCurrentVerifiedLicense(ProfessionalApplication application) {
        boolean hasCurrentLicense = documentsFor(application)
            .stream()
            .anyMatch(
                d ->
                    d.getType() == DocumentType.LICENSE &&
                    d.getVerificationStatus() == VerificationStatus.VERIFIED &&
                    d.getExpiryDate() != null &&
                    !d.getExpiryDate().isBefore(java.time.LocalDate.now())
            );
        if (!hasCurrentLicense) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reactivation requires a verified, unexpired license");
        }
    }

    public List<ProfessionalApplication> listApplications(OnboardingStatus status) {
        if (status == null) {
            return applicationRepository.findAll(
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "submittedAt")
            );
        }
        return applicationRepository.findByStatusOrderBySubmittedAtDesc(status);
    }

    /** Reviewer access to an application's documents; bytes stripped by the resource layer. */
    public List<PersonalDocument> documentsForApplication(String applicationId) {
        return documentsFor(getById(applicationId));
    }

    public PersonalDocument verifyDocument(String documentId, String actor) {
        PersonalDocument document = requireDocument(documentId);
        document.verificationStatus(VerificationStatus.VERIFIED).verifiedBy(actor).verifiedAt(Instant.now()).rejectionReason(null);
        return personalDocumentRepository.save(document);
    }

    public PersonalDocument rejectDocument(String documentId, String reason, String actor) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rejecting a document requires a reason");
        }
        PersonalDocument document = requireDocument(documentId);
        document.verificationStatus(VerificationStatus.REJECTED).verifiedBy(actor).verifiedAt(Instant.now()).rejectionReason(reason);
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

    private ProfessionalApplication transition(ProfessionalApplication application, OnboardingStatus to, String actor, String reason) {
        OnboardingStatus from = application.getStatus();
        if (from == null || !LEGAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Illegal onboarding transition " + from + " -> " + to);
        }
        application.status(to);
        ProfessionalApplication saved = applicationRepository.save(application);
        appendEvent(saved, from, to, reason);
        return saved;
    }

    private void appendEvent(ProfessionalApplication application, OnboardingStatus from, OnboardingStatus to, String reason) {
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
        List<PersonalDocument> documents = profile == null || profile.getId() == null
            ? List.<PersonalDocument>of()
            : personalDocumentRepository.findByProfileId(profile.getId());

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

    private static boolean nextOfKinComplete(Profile profile) {
        return (
            profile != null &&
            profile.getEmergencyContact() != null &&
            hasText(profile.getEmergencyContact().getName()) &&
            hasText(profile.getEmergencyContact().getRelationship()) &&
            hasText(profile.getEmergencyContact().getPhone())
        );
    }

    private void requireMandatoryDocuments(ProfessionalApplication application) {
        List<PersonalDocument> documents = documentsFor(application);
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

    private void requireAllMandatoryDocumentsVerified(ProfessionalApplication application) {
        List<PersonalDocument> documents = documentsFor(application);
        boolean allVerified =
            !documents.isEmpty() && documents.stream().allMatch(d -> d.getVerificationStatus() == VerificationStatus.VERIFIED);
        if (!allVerified) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval requires every uploaded document to be verified");
        }
    }

    private List<PersonalDocument> documentsFor(ProfessionalApplication application) {
        String profileId = application.getProfileId();
        if (profileId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Application has no linked profile");
        }
        return personalDocumentRepository.findByProfileId(profileId);
    }
}
