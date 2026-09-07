package net.jojoaddison.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import net.jojoaddison.repository.OnboardingEventRepository;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * WP7 ongoing-compliance operations (professional-onboarding-workflow.md
 * step 12): the license-expiry sweep that restricts ACTIVE professionals whose
 * license has lapsed, the expiring-soon watchlist, and the per-status /
 * per-source funnel metrics (careers task 145). Restriction goes through the
 * WP3 state machine (ACTIVE -> SUSPENDED with an audited reason) — never a
 * direct status write; reactivation is guarded in {@link OnboardingService}.
 */
@Service
public class ComplianceService {

    public static final String LICENSE_EXPIRED_REASON = "license-expired";
    /** Sources are free-form (careers contract) — applications without one count as direct signups. */
    public static final String DIRECT_SOURCE = "direct";

    private static final Logger log = LoggerFactory.getLogger(ComplianceService.class);

    private final OnboardingService onboardingService;
    private final ProfessionalApplicationRepository applicationRepository;
    private final PersonalDocumentRepository personalDocumentRepository;
    private final OnboardingEventRepository eventRepository;
    private final DomainEventPublisher domainEventPublisher;

    public ComplianceService(
        OnboardingService onboardingService,
        ProfessionalApplicationRepository applicationRepository,
        PersonalDocumentRepository personalDocumentRepository,
        OnboardingEventRepository eventRepository,
        DomainEventPublisher domainEventPublisher
    ) {
        this.onboardingService = onboardingService;
        this.applicationRepository = applicationRepository;
        this.personalDocumentRepository = personalDocumentRepository;
        this.eventRepository = eventRepository;
        this.domainEventPublisher = domainEventPublisher;
    }

    public record SweepResult(int expiredLicenses, int applicationsSuspended) {}

    public record ExpiringLicense(
        String documentId,
        String profileId,
        String applicationId,
        String accountId,
        String login,
        LocalDate expiryDate,
        String verificationStatus
    ) {}

    public record OnboardingMetrics(Map<String, Long> byStatus, Map<String, Long> bySource, long expiringLicenses30d) {}

    /**
     * Suspend every ACTIVE application whose license expired before today.
     * Idempotent: already-suspended applications are counted but not
     * re-transitioned, so the scheduler can run daily without duplicating
     * audit events.
     * <p>
     * A professional who has since <b>renewed</b> is skipped entirely — not suspended, no
     * {@code compliance.alert}, no {@code license-expired} audit event. Renewing was a pure insert
     * that retired nothing, so the lapsed row stayed in the collection for ever; without this guard
     * the nightly sweep found it and undid the administrator's reinstatement at 04:00, every night,
     * blaming a licence that was valid (backlog.md item 17). The guard asks
     * {@link OnboardingService#hasCurrentVerifiedLicense} — the same predicate reactivation is
     * granted on — because the defect was precisely that the two questions had separate answers.
     * <p>
     * <b>Item 20 added a second, independent defence and did not replace this one.</b> A renewal
     * uploaded through {@code /api/onboarding/documents} marks the row the clinician names as
     * replaced, and the query below skips marked rows, so the loop never reaches the guard for that
     * professional. The guard still has to be here, and it covers strictly more: the profiles whose
     * lapsed rows predate the marker, rows renewed by any other path, and — since a clinician who
     * does not name the row they are replacing has still renewed — every upload that marks nothing.
     * Either defence alone closes the lockout; the test that proves the guard still works
     * ({@code ComplianceFlowIT.expiredLicenseSweepRestrictsAndReactivationNeedsANewLicense}) renews
     * through the repository rather than the upload endpoint precisely so that the marker is absent
     * and the guard is the only thing that can be answering.
     */
    public SweepResult sweepExpiredLicenses(String actor) {
        List<PersonalDocument> expired = personalDocumentRepository.findByTypeAndExpiryDateLessThanAndSupersededAtIsNull(
            DocumentType.LICENSE,
            LocalDate.now()
        );
        int suspended = 0;
        int renewed = 0;
        for (PersonalDocument license : expired) {
            ProfessionalApplication application = applicationRepository.findByProfileId(license.getProfileId()).orElse(null);
            if (application == null || application.getStatus() != OnboardingStatus.ACTIVE) {
                continue;
            }
            if (onboardingService.hasCurrentVerifiedLicense(license.getProfileId())) {
                // "Skipped", not "superseded": the row reaching this branch is one the marker did not
                // catch, so borrowing the field's name for it would read as the opposite of the truth.
                log.debug(
                    "Compliance sweep: profile {} holds a current license; lapsed {} skipped",
                    license.getProfileId(),
                    license.getId()
                );
                renewed++;
                continue;
            }
            onboardingService.markStatus(
                application.getId(),
                OnboardingStatus.SUSPENDED,
                LICENSE_EXPIRED_REASON + ": " + license.getId() + " expired " + license.getExpiryDate(),
                actor
            );
            domainEventPublisher.publishComplianceAlert(LICENSE_EXPIRED_REASON, license.getId(), application.getAccountId(), actor);
            suspended++;
        }
        log.info(
            "Compliance sweep: {} expired licenses, {} applications suspended, {} skipped as renewed",
            expired.size(),
            suspended,
            renewed
        );
        return new SweepResult(expired.size(), suspended);
    }

    /**
     * Licenses already lapsed or lapsing within the window, joined to their application for the ops
     * view.
     *
     * <p>Already-expired rows are included deliberately — a watchlist that dropped a licence the day
     * it lapsed would hide exactly the professionals ops most needs to chase. <b>Superseded rows are
     * not</b> (backlog.md item 20): before the marker existed this joined every LICENSE a profile had
     * ever held, so a clinician who renewed last year stayed on the list for ever and there was no
     * action that would clear them off it.
     */
    public List<ExpiringLicense> expiringLicenses(int days) {
        LocalDate today = LocalDate.now();
        // "LessThan" is exclusive -> everything with expiryDate <= today+days, including already-expired
        return personalDocumentRepository
            .findByTypeAndExpiryDateLessThanAndSupersededAtIsNull(DocumentType.LICENSE, today.plusDays(Math.max(0, days) + 1L))
            .stream()
            .map(license -> {
                ProfessionalApplication application = applicationRepository.findByProfileId(license.getProfileId()).orElse(null);
                return new ExpiringLicense(
                    license.getId(),
                    license.getProfileId(),
                    application == null ? null : application.getId(),
                    application == null ? null : application.getAccountId(),
                    application == null ? null : application.getLogin(),
                    license.getExpiryDate(),
                    license.getVerificationStatus() == null ? null : license.getVerificationStatus().name()
                );
            })
            .sorted((a, b) -> a.expiryDate().compareTo(b.expiryDate()))
            .toList();
    }

    /** Funnel counts by status and by attribution source (careers task 145). */
    public OnboardingMetrics metrics() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> bySource = new LinkedHashMap<>();
        for (ProfessionalApplication application : applicationRepository.findAll()) {
            String status = application.getStatus() == null ? "UNKNOWN" : application.getStatus().name();
            byStatus.merge(status, 1L, Long::sum);
            String source = application.getSource() == null || application.getSource().isBlank() ? DIRECT_SOURCE : application.getSource();
            bySource.merge(source, 1L, Long::sum);
        }
        // Through expiringLicenses rather than a second query of its own, so the headline number and
        // the list an operator opens from it can never disagree about what counts — including about
        // superseded rows, which neither counts (backlog.md item 20).
        long expiringSoon = expiringLicenses(30).size();
        return new OnboardingMetrics(byStatus, bySource, expiringSoon);
    }

    /** Most recent audit events across all applications, newest first (admin audit viewer). */
    public List<net.jojoaddison.domain.OnboardingEvent> recentEvents() {
        return eventRepository.findTop50ByOrderByAtDesc();
    }
}
