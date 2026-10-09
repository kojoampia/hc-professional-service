package net.jojoaddison.web.rest;

import java.util.List;
import net.jojoaddison.domain.OnboardingEvent;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.ComplianceService;
import net.jojoaddison.service.OnboardingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for {@link ProfessionalApplication} — {@code api/professional-application}
 * (profile.md step 4).
 *
 * <h2>⭐ The file the specification names, and it serves one base path (T3)</h2>
 *
 * <p>{@code profile.md} step 4 names this file by path:
 * <i>"Endpoint: service: {@code api/professional-application} - create if does not exist …
 * {@code src/main/java/net/jojoaddison/web/rest/ProfessionalApplicationResource.java}"</i>. Finding
 * F3 settled what that means: <b>when the specification names a file, the endpoint is served from
 * that file</b> — so this class holds the applicant's surface and the administrator's, and
 * {@code ProfileResource} is the precedent to read before editing it.
 *
 * <p>There is <b>no class-level {@code @RequestMapping}</b>, because a class-level prefix cannot be
 * opted out of per handler and the three sub-surfaces below do not share a gate. Each handler
 * spells its own path out of {@link #BASE}.
 *
 * <table>
 *   <caption>The three sub-surfaces of one base path</caption>
 *   <tr><th>path</th><th>subject</th><th>gate</th></tr>
 *   <tr><td>{@code /me}, {@code /me/**}, and {@code POST} on the bare path</td>
 *       <td>none; the caller's own, from the {@code uid} claim</td>
 *       <td>{@code .authenticated()}</td></tr>
 *   <tr><td>{@code /&#123;id&#125;} reads</td><td>named in the path</td>
 *       <td>{@code .authenticated()} + an owner check in the method</td></tr>
 *   <tr><td>{@code GET} on the bare path, the seven {@code /&#123;id&#125;} transitions, and
 *       {@code /compliance/**}</td><td>every application, or one named in the path</td>
 *       <td>{@code ROLE_ADMIN}</td></tr>
 * </table>
 *
 * <h2>⛔ What moved here, and from where</h2>
 *
 * <p>{@code profile.md} § Other Elements: <i>"{@code api/onboarding/applications} should migrate to
 * {@code api/professional-application}"</i>, and § Gap Update: <i>"Refactor the rest of
 * {@code onboarding/compliance} and {@code /applications} into those above."</i> So <b>nineteen
 * mappings arrived here</b> — the fifteen {@code /applications*} mappings
 * {@code OnboardingResource} carried and the four {@code ComplianceResource} carried, that class
 * being deleted rather than re-based, because two classes claiming one base path is the shape
 * {@code OnboardingDocumentResource}'s javadoc called a transitional state and not a design.
 *
 * <p>⚠ <b>{@code OnboardingResource} survives with three mappings</b> — {@code /progress} and the
 * two {@code /acknowledgement} verbs. They are <b>T5's</b>, {@code mobile/} still reads
 * {@code /progress}, and this task deliberately left them where they were.
 *
 * <h2>The sub-paths are preserved under the new base, deliberately</h2>
 *
 * <p>{@code /applications/&#123;id&#125;/decide} became {@code /&#123;id&#125;/decide} and
 * {@code /compliance/sweep} stayed {@code /compliance/sweep}: a prefix migration rather than a
 * re-design, so every consumer's change is one base URL and a reviewer can read the two lists
 * against each other. ⚠ <b>{@code /me} and {@code /compliance} are literal segments competing with
 * {@code /&#123;id&#125;}</b>; Spring's {@code PathPattern} comparator prefers the literal, which is
 * exactly the arrangement {@code /applications/me} and {@code /applications/&#123;id&#125;} already
 * had, so this is not a new property to prove.
 *
 * @see OnboardingService the state machine every status write here goes through
 */
@RestController
public class ProfessionalApplicationResource {

    private static final Logger log = LoggerFactory.getLogger(ProfessionalApplicationResource.class);

    /**
     * The one base path this class serves. Every handler spells its own path out of this constant
     * because <b>there is no class-level {@code @RequestMapping}</b> — see the class comment.
     */
    private static final String BASE = "/api/professional-application";

    /** The caller's own application; no subject in any path below it. */
    private static final String OWN = BASE + "/me";

    /** The administrator's compliance and operations surface, {@code ROLE_ADMIN} throughout. */
    private static final String COMPLIANCE = BASE + "/compliance";

    private final OnboardingService onboardingService;

    private final ComplianceService complianceService;

    public ProfessionalApplicationResource(OnboardingService onboardingService, ComplianceService complianceService) {
        this.onboardingService = onboardingService;
        this.complianceService = complianceService;
    }

    /**
     * Step 4's body: the consent tick and the authority being applied for, plus the careers
     * attribution on the create.
     *
     * <h2>An allow-list record rather than the entity</h2>
     *
     * <p>Same argument {@code OwnPersonalDocumentResource.PersonalDocumentUpload} and T4's
     * {@code OwnAccountDTO} settled. {@link ProfessionalApplication} carries fourteen fields and
     * eleven of them are the server's or the reviewer's — {@code status}, {@code decidedBy},
     * {@code decidedAt}, {@code decisionReason}, {@code correctionNotes}, {@code submittedAt},
     * {@code agreedDate}, {@code profileId}, {@code accountId}, {@code login}, {@code invitedBy} —
     * so binding the entity would need a refusal map beside it, and an applicant posting
     * {@code "status": "ACTIVE"} would have granted themselves clinical access. <b>A deny-list must
     * track an entity that grows; an allow-list cannot acquire a field through someone else's
     * edit.</b>
     *
     * <p>⚠ <b>{@code agreedDate} is deliberately not a component.</b> {@code profile.md} types it
     * <i>"Server stamp"</i>, so there is no shape in which a client could send one.
     *
     * @param agreed step 4's consent tick. {@code false} is refused with 400 — see
     *     {@link OnboardingService#CONSENT_REQUIRED}.
     * @param authority the role string applied for. A {@code String} because {@code Authority} is
     *     the gateway's class and this service holds only the role (profile.md § Gap Update).
     * @param source the careers attribution, e.g. {@code web-careers}; read by the review queue and
     *     the WP7 funnel count (careers-handoff-contract.md § 3). <b>Owned by the create, and
     *     refused rather than ignored on the other two (F-F)</b> — see
     *     {@link #refuseSourceOnAWriteThatDoesNotOwnIt}.
     */
    public record ApplicationConsentRequest(boolean agreed, String authority, String source) {}

    public record DecisionRequest(ProfileStatus decision, String reason, String correctionNotes) {}

    public record OrganizationRequest(String specialtyCategoryId, List<String> teamIds, String supervisorProfileId) {}

    public record StatusRequest(String reason) {}

    // ---------------------------------------------------------------------------------------------
    // The applicant's own application. No subject in any path in this block.
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code POST /api/professional-application} : starts the caller's application, recording
     * consent and the requested authority.
     *
     * <p><b>Three behaviours carried across from {@code POST /api/professional-application}
     * unchanged</b>, because {@code profile.md}'s silence about them is not permission to drop
     * them: {@code agreed: false} is 400, a second call is <b>409</b> <i>"An application already
     * exists for this account"</i>, and {@code agreedDate} is stamped server-side.
     * {@code profileId} is set from the caller's own {@code Profile} where they have one, which is
     * what step 4's <i>"Set to {@code Profile.id}"</i> asks for.
     *
     * @return the created application, 201.
     */
    @PostMapping(BASE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ProfessionalApplication> startApplication(@RequestBody ApplicationConsentRequest request) {
        String accountId = currentAccountId();
        log.debug("REST request to start a professional application for {}", accountId);
        ProfessionalApplication application = onboardingService.startApplication(
            accountId,
            currentActor(),
            request.authority(),
            request.agreed(),
            null,
            request.source()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(application);
    }

    /**
     * {@code GET /api/professional-application/me} : the caller's own application.
     *
     * <p>404 when they have never applied, which is the normal state for every clinician seeded or
     * invited rather than hired through the careers page. Both clients treat it as such — see
     * {@code web/}'s {@code error-handler.interceptor.ts}, which opts this read out of the global
     * error banner because the untreated 404 was visible in the deployed portal.
     */
    @GetMapping(OWN)
    @PreAuthorize("isAuthenticated()")
    public ProfessionalApplication getOwnApplication() {
        return onboardingService.getOwnApplication(currentAccountId());
    }

    /**
     * {@code PUT /api/professional-application/me} : <b>step 4's Save</b> — <b>non-advancing when the
     * application is incomplete</b> (owner decision 2026-10-09).
     *
     * <p>Always stores the consent and the requested authority, and <em>then</em> advances to
     * {@code CREDENTIAL_REVIEW} only if every requirement is satisfied. An incomplete application
     * answers <b>200 with the application as stored</b> — no transition, no Kafka event, no refusal:
     * an applicant saving their answers mid-wizard is not making a mistake.
     *
     * <p>⛔ <b>It served {@link OnboardingService#submitForReview} until this decision</b>, which made
     * the two buttons one operation on the reading that {@code profile.md} step 4 attributes
     * identical effects to both. They share one body still — {@code storeThenAdvanceWhenComplete} —
     * and differ in one argument, so the storing and the completeness evaluation cannot drift apart.
     * {@link #submitForReview} is the path that refuses.
     *
     * <p>⚠ <b>Repeatable.</b> Save checks whether {@code CREDENTIAL_REVIEW} is legal from the current
     * status before transitioning rather than letting the state machine refuse, so calling it twice —
     * and calling it on an application already in review — stores and answers 200. The 409 that used
     * to come from this path belongs to {@link #submitForReview} now.
     *
     * <p>⛔ <b>When it does advance, the status write goes through {@code transition()}</b>, not an
     * assignment: a write that checks nothing and appends nothing leaves an application whose own
     * history does not contain the step, and hc-admin would never be told it was queued.
     */
    @PutMapping(OWN)
    @PreAuthorize("isAuthenticated()")
    public ProfessionalApplication saveConsent(@RequestBody ApplicationConsentRequest request) {
        String accountId = currentAccountId();
        log.debug("REST request to save the consent and authority on {}'s own application", accountId);
        refuseSourceOnAWriteThatDoesNotOwnIt(request);
        return onboardingService.saveConsent(accountId, request.agreed(), request.authority());
    }

    /**
     * {@code PUT /api/professional-application/me/submit} : <b>step 4's Submit</b>, under the path
     * {@code /applications/me/submit} migrated to.
     *
     * <p>§ Gap Update: <i>"Submitting sets {@code Application.status} to {@code CREDENTIAL_REVIEW}
     * and triggers the Kafka event, <b>when all requirements are satisfied</b>."</i> All of it is the
     * service method's, and the last clause is the <b>only</b> thing that distinguishes this handler
     * from {@link #saveConsent}: where Save stores and stays put, this <b>refuses with 400 naming the
     * unsatisfied requirement keys</b> — step 2 by {@code ProfileCompleteness}, step 3's four
     * documents, step 4's authority.
     *
     * <p>A second Submit on an application already in {@code CREDENTIAL_REVIEW} is the state
     * machine's <b>409</b>, which is where that answer belongs: Submit means "I am finished", and
     * saying it twice is a conflict in a way that saving twice is not.
     */
    @PutMapping(OWN + "/submit")
    @PreAuthorize("isAuthenticated()")
    public ProfessionalApplication submitForReview(@RequestBody ApplicationConsentRequest request) {
        String accountId = currentAccountId();
        log.debug("REST request to submit {}'s own application for credential review", accountId);
        refuseSourceOnAWriteThatDoesNotOwnIt(request);
        return onboardingService.submitForReview(accountId, request.agreed(), request.authority());
    }

    /**
     * {@code PUT /api/professional-application/me/complete-profile} : links the caller's profile and
     * moves {@code APPLICATION_STARTED → PROFILE_COMPLETED}.
     *
     * <p>Migrated unchanged from {@code /applications/me/complete-profile}. It is the step the state
     * machine requires between starting and review, so step 4's write is a 409 without it.
     */
    @PutMapping(OWN + "/complete-profile")
    @PreAuthorize("isAuthenticated()")
    public ProfessionalApplication completeProfile() {
        return onboardingService.completeProfile(currentAccountId());
    }

    // ---------------------------------------------------------------------------------------------
    // Reads that name an application in the path: admin, or the applicant it belongs to.
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code GET /api/professional-application/&#123;id&#125;} : one application.
     *
     * <p>⚠ <b>{@code .authenticated()} with an owner check in the method, not {@code ROLE_ADMIN}</b>
     * — and that is not the self-carve-out the estate rule forbids on a subject-addressed path. The
     * applicant's own path is {@link #getOwnApplication}, which cannot name anyone else; this
     * handler exists for the reviewer, and the owner clause is what the review-detail timeline and
     * the applicant's own status view shared before the migration. It is carried across rather than
     * decided here.
     */
    @GetMapping(BASE + "/{id}")
    @PreAuthorize("isAuthenticated()")
    public ProfessionalApplication getApplication(@PathVariable String id) {
        ProfessionalApplication application = onboardingService.getById(id);
        assertAdminOrOwner(application);
        return application;
    }

    /**
     * {@code GET /api/professional-application/&#123;id&#125;/documents} : the application's
     * documents, bytes stripped.
     *
     * <p>Archived rows included — a superseded licence is evidence of what a clinician held while
     * they were treating patients (backlog.md item 20).
     */
    @GetMapping(BASE + "/{id}/documents")
    @PreAuthorize("isAuthenticated()")
    public List<PersonalDocument> applicationDocuments(@PathVariable String id) {
        assertAdminOrOwner(onboardingService.getById(id));
        return onboardingService
            .documentsForApplication(id)
            .stream()
            .map(document -> {
                document.setData(null);
                return document;
            })
            .toList();
    }

    /** {@code GET /api/professional-application/&#123;id&#125;/events} : the append-only transition trail. */
    @GetMapping(BASE + "/{id}/events")
    @PreAuthorize("isAuthenticated()")
    public List<OnboardingEvent> getEvents(@PathVariable String id) {
        assertAdminOrOwner(onboardingService.getById(id));
        return onboardingService.eventsFor(id);
    }

    // ---------------------------------------------------------------------------------------------
    // The reviewer's and administrator's surface. ROLE_ADMIN on every handler below.
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code GET /api/professional-application} : the review queue, optionally filtered by status.
     *
     * <p>The widest read here — it names no subject because it returns every one of them, which is
     * why it is {@code ROLE_ADMIN} where the {@code /&#123;id&#125;} reads above admit the owner
     * too. Its {@code HEAD} is gated in the filter chain beside its {@code GET}: Spring MVC
     * dispatches a {@code HEAD} to the {@code @GetMapping} handler, and a body-less read of a queue
     * still answers how many people are waiting (backlog.md item 143).
     */
    @GetMapping(BASE)
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public List<ProfessionalApplication> listApplications(@RequestParam(value = "status", required = false) ProfileStatus status) {
        return onboardingService.listApplications(status);
    }

    @PutMapping(BASE + "/{id}/decide")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication decide(@PathVariable String id, @RequestBody DecisionRequest request) {
        return onboardingService.decide(id, request.decision(), request.reason(), request.correctionNotes(), currentActor());
    }

    @PutMapping(BASE + "/{id}/organization")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication assignOrganization(@PathVariable String id, @RequestBody OrganizationRequest request) {
        return onboardingService.assignOrganization(
            id,
            request.specialtyCategoryId(),
            request.teamIds(),
            request.supervisorProfileId(),
            currentActor()
        );
    }

    @PutMapping(BASE + "/{id}/authority-assigned")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication markAuthorityAssigned(@PathVariable String id) {
        return onboardingService.markStatus(id, ProfileStatus.AUTHORITY_ASSIGNED, "clinical authority assigned", currentActor());
    }

    @PutMapping(BASE + "/{id}/roster-configured")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication markRosterConfigured(@PathVariable String id) {
        return onboardingService.markStatus(id, ProfileStatus.ROSTER_CONFIGURED, "duty roster configured", currentActor());
    }

    @PutMapping(BASE + "/{id}/activate")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication activate(@PathVariable String id) {
        return onboardingService.markStatus(id, ProfileStatus.ACTIVE, "professional access activated", currentActor());
    }

    @PutMapping(BASE + "/{id}/suspend")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication suspend(@PathVariable String id, @RequestBody StatusRequest request) {
        return onboardingService.markStatus(id, ProfileStatus.SUSPENDED, request.reason(), currentActor());
    }

    @PutMapping(BASE + "/{id}/deactivate")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication deactivate(@PathVariable String id, @RequestBody StatusRequest request) {
        return onboardingService.markStatus(id, ProfileStatus.DEACTIVATED, request.reason(), currentActor());
    }

    // ---------------------------------------------------------------------------------------------
    // WP7 compliance and operations, absorbed from ComplianceResource (profile.md § Gap Update:
    // "Refactor the rest of onboarding/compliance and /applications into those above"). On-demand
    // expiry sweep, expiring-licence watchlist, per-status/per-source funnel metrics (careers task
    // 145) and the cross-application audit feed. ROLE_ADMIN throughout — the gate is per handler
    // here rather than on the class, because this class does not have one answer to who may call it.
    // ---------------------------------------------------------------------------------------------

    @PostMapping(COMPLIANCE + "/sweep")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ComplianceService.SweepResult sweep() {
        String actor = currentActor();
        log.debug("REST request by {} to run the compliance sweep", actor);
        return complianceService.sweepExpiredLicenses(actor);
    }

    @GetMapping(COMPLIANCE + "/expiring")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public List<ComplianceService.ExpiringLicense> expiring(@RequestParam(defaultValue = "30") int days) {
        return complianceService.expiringLicenses(days);
    }

    @GetMapping(COMPLIANCE + "/metrics")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ComplianceService.OnboardingMetrics metrics() {
        return complianceService.metrics();
    }

    @GetMapping(COMPLIANCE + "/events")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public List<OnboardingEvent> recentEvents() {
        return complianceService.recentEvents();
    }

    /**
     * ⛔ <b>{@code source} belongs to the create, and Save and Submit refuse it rather than dropping
     * it (F-F).</b>
     *
     * <p>{@link ApplicationConsentRequest} is bound by all three write handlers, and the service
     * method behind the other two has no {@code source} parameter at all — so a client sending one
     * had it <b>silently ignored</b>: 200, the attribution unchanged, and nothing to tell the caller
     * their field went nowhere. That is the exact failure {@code ProfileFieldOwnership} exists to
     * refuse, one file along, and it states the rule this method applies:
     * <i>"Refuses, rather than silently ignores, a write that would change a field the calling
     * endpoint does not own"</i>, with a message naming the field and the endpoint that does own it.
     *
     * <p><b>Why the create owns it.</b> {@code source} is the careers attribution
     * (careers-handoff-contract.md § 3) — where a clinician came from is a fact about the
     * application's <em>origin</em>, so a later write cannot restate it. Honouring it here would also
     * let step 4 overwrite a real {@code web-careers} attribution with {@code null} on every Save, by
     * a client that merely round-tripped the record it had read; refusing the component is what makes
     * that unrepresentable rather than guarded against.
     *
     * <p>⚠ <b>Refused on the mention, not on a change</b> — unlike
     * {@code ProfileFieldOwnership.refuseFieldsThisEndpointDoesNotOwn}, which tolerates a field named
     * at its current value because its callers accept whole documents a client had just read. These
     * two handlers take a three-component record that a client composes, not a document it echoes, so
     * there is no honest reason to name {@code source} here at all. ⚠ A JSON body omitting the key and
     * one sending {@code "source": null} are indistinguishable after binding — a record component is
     * simply null for both — so the refusal necessarily keys on a <em>value</em> being present.
     * {@code profile.md} does not mention {@code source}, so this follows the house rule rather than a
     * reading of the specification.
     */
    private void refuseSourceOnAWriteThatDoesNotOwnIt(ApplicationConsentRequest request) {
        if (request.source() != null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "This endpoint does not set these fields, and will not quietly drop them: " +
                "source is set by POST " +
                BASE +
                ", which records the careers attribution once when the application is created"
            );
        }
    }

    private void assertAdminOrOwner(ProfessionalApplication application) {
        boolean admin = SecurityUtils.hasCurrentUserAnyOfAuthorities(AuthoritiesConstants.ADMIN);
        if (!admin && !currentAccountId().equals(application.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the application owner");
        }
    }

    /**
     * The caller's gateway {@code User.id} — what {@code ProfessionalApplication.accountId} and
     * {@code Profile.accountId} hold and are looked up by (backlog.md item 50).
     *
     * <p>A token carrying no {@code uid} claim — one minted before 2026-09-07, or by hc-admin or
     * hc-patient — resolves to nobody and is refused here rather than falling back to the subject,
     * which would key this database on a value it no longer stores. Signing in again mints a token
     * that carries the claim.
     */
    private String currentAccountId() {
        return SecurityUtils.getCurrentAccountId()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
    }

    /**
     * The caller's login, for the {@code actor} on an {@code OnboardingEvent} and on the domain
     * events a transition publishes. Deliberately not {@link #currentAccountId()}: a trail an
     * administrator reads should name a person, and nothing is ever looked up by it.
     */
    private String currentActor() {
        return SecurityUtils.getCurrentUserLogin().orElse("system");
    }
}
