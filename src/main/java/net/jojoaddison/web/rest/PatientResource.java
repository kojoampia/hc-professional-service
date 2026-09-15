package net.jojoaddison.web.rest;

import java.util.List;
import java.util.stream.Collectors;
import net.jojoaddison.service.PatientDirectoryService;
import net.jojoaddison.service.dto.PatientDtos.ActivityLogEntry;
import net.jojoaddison.service.dto.PatientDtos.CaseDetail;
import net.jojoaddison.service.dto.PatientDtos.CaseSummary;
import net.jojoaddison.service.dto.PatientDtos.ClinicalReport;
import net.jojoaddison.service.dto.PatientDtos.CreateActivity;
import net.jojoaddison.service.dto.PatientDtos.CreateReport;
import net.jojoaddison.service.dto.PatientDtos.PatientListItem;
import net.jojoaddison.service.dto.PatientDtos.PatientRecord;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tech.jhipster.web.util.PaginationUtil;

/**
 * The calling clinician's patients — those they have worked with or are working with now.
 *
 * <p>This is a professional-scoped view, not a patient registry. professionalservice owns the
 * relation (which clinician is attached to which patient); patientservice owns the people. The list
 * is therefore always "my patients" and never "all patients", and there is deliberately no way to
 * ask for someone else's.
 *
 * <p><b>Reads, plus two writes that forward rather than own.</b> Creating patients and cases still
 * belongs to patientservice. The two POSTs below file an activity entry and a report against a
 * patient the caller already has, and they do it by relaying the caller's own token to
 * patientservice — this service adds the entitlement check its sibling cannot make, and stores
 * nothing of the record but an idempotency receipt.
 *
 * <p><b>Every {@code catch} below names one exception, and that is deliberate</b> (backlog item 24).
 * The directory is computed from collections read across the wire, so
 * {@link net.jojoaddison.service.PatientServiceUnavailableException} passes through these handlers
 * untouched and {@code ExceptionTranslator} answers 503. Widening any of them — to
 * {@code RuntimeException}, or to a bare {@code catch (Exception)} — would put the outage back behind
 * a 404 saying the patient is not this clinician's, which is the defect that item exists to close.
 */
@RestController
@RequestMapping("/api/patients")
public class PatientResource {

    /**
     * Names the composed parts the caller's discipline may not read (backlog item 107).
     *
     * <p><b>On two responses now, not one</b> (backlog item 112): the directory below, and one patient's
     * record. The vocabulary is the same on both — {@link PatientDirectoryService.RestrictedPart#token()}
     * — and deliberately so, because the collection that was refused is the same collection. What the
     * token <em>costs</em> differs by response, and that is the reading client's business rather than
     * this header's: {@code lastActivity} blanks a column on the list and the activity panel plus
     * {@code lastActivityAt} on a record. {@code caseAssignments} reaches the list only, since a record
     * whose case read was refused cannot decide entitlement and is not served at all.
     *
     * <p><b>A header rather than a wider body, and that was the trade.</b> {@code web/} and
     * {@code mobile/} both consume this endpoint's body as a bare JSON array, so an envelope naming the
     * restriction would have broken two deployed clients on the release that shipped it — and neither
     * is this item's to change. The cost is the honest one: a header can be ignored, and a client that
     * ignores it renders a blank recency column exactly as it renders a caseload nobody has touched.
     * That is the conflation this item exists to remove, and closing it needs the two frontends to read
     * the header; the value here is what makes that possible rather than what completes it.
     *
     * <p>Absent entirely when nothing was restricted — the ordinary case, and the one that should pay
     * nothing. Values are {@link PatientDirectoryService.RestrictedPart#token()}, comma-separated, in
     * enum declaration order so the header is byte-stable between requests.
     *
     * <p><b>Same-origin only, as it stands.</b> A browser cannot read a response header that is not in
     * {@code Access-Control-Expose-Headers}; {@code web/} reaches this through nginx and the webpack
     * proxy, so it is same-origin in both deployments and the question does not arise. It would arise
     * for any cross-origin consumer, and the gateway's CORS configuration is where it would be
     * answered — not here.
     */
    static final String RESTRICTED_PARTS = "X-Restricted-Parts";

    /**
     * Names a read reachable <em>from</em> the directory that the same refusal also blocks — so that a
     * clinician handed a list of rows that will not open learns it before tapping one (backlog item 128).
     *
     * <p><b>The condition item 112 is named for, still true for one discipline.</b> Item 112 refused to
     * degrade a technician's record and argued it well: hc-patient admits them to no clinical domain, so
     * a degraded record would be a name, a birth date and a phone number beside four empty lists. That
     * settles the record and leaves the <em>list</em> — a hundred rows on the quality stack, measured
     * 2026-09-15, of which not one opens, with nothing on the wire to mark them by.
     * {@link #RESTRICTED_PARTS} cannot say it: it names what was withheld from <em>this</em> read, which
     * is a different sentence from <em>and therefore that one will refuse you</em>.
     *
     * <p><b>A second header rather than a token on the first, and that is not a style choice.</b>
     * {@link PatientDirectoryService.RestrictedPart} means "a composed part of this read that was
     * withheld", and a follow-up endpoint is not a part of this read — a pseudo-token would be nonsense
     * on {@link #get}, which shares that vocabulary, and would be counted by
     * {@code PatientDirectoryRestrictionMeters}, whose one call site exists precisely so the metric and
     * {@link #RESTRICTED_PARTS} name the same parts (backlog item 116). A separate header is additive in
     * the way item 111's Decision A required: the body is untouched, and {@code web/} and {@code mobile/}
     * ignore an unknown header as they ignored this one until item 114.
     *
     * <p><b>Whether it is <em>true</em> is the question this row turned on, and the answer is that it is
     * a statement about the past.</b> It says: a part was withheld from this read that
     * {@code GET /api/patients/&#123;id&#125;} requires and does not tolerate losing. Both halves are
     * established rather than guessed — the refusal was observed by the read that emitted this header,
     * and the record path's strictness is this service's own code, held to the flag it is rendered from
     * by an exhaustive test. What a client draws from it is a prediction, exactly as it is for a
     * {@code lastActivity} column rendered "not permitted"; it goes stale at the rate the directory does,
     * and if hc-patient's matrix moves the next read stops emitting it. <b>No discipline is written down
     * here or anywhere it is derived from</b> — that copy would be the drift backlog item 116 exists to
     * catch.
     *
     * <p>Values are tokens, comma-separated, absent entirely when nothing is blocked. {@link #RECORD} is
     * the only one, and {@code /api/patients/&#123;id&#125;/cases} is deliberately <b>not</b> named
     * beside it although it refuses the same callers for the same reason.
     *
     * <p><b>That was left open as backlog item 127 and is now settled, and a second token was still
     * refused.</b> Item 127 decided the cases endpoint keeps refusing — see
     * {@link PatientDirectoryService#casesFor}, which argues it — so a {@code cases} token here would at
     * least be true. It buys a client nothing. The refusal is the same refusal, on the same collection,
     * observed by the same read: any caller told {@link #RECORD} is told {@code cases} too and never one
     * without the other, so a second value would be a flag whose value is a copy of the first's, and a
     * copy is what goes stale while looking maintained. The record is also the only one of the two a
     * directory row leads to — a client that marks a row unopenable has already said everything a
     * {@code cases} token could add, because the cases screen is reached <em>through</em> the record.
     * A token earns its place by letting a client render something different; this one would not.
     *
     * <p><b>Same-origin only, as {@link #RESTRICTED_PARTS} is</b> — a browser cannot read a response
     * header absent from {@code Access-Control-Expose-Headers}, and both deployments of {@code web/} are
     * same-origin, so the question arises only for a cross-origin consumer and is the gateway's to answer.
     */
    static final String RESTRICTED_FOLLOW_UPS = "X-Restricted-Follow-Ups";

    /**
     * The one follow-up token: {@code GET /api/patients/{id}}, the record behind a directory row.
     *
     * <p>Spelled here rather than derived from a type name, for {@link #RESTRICTED_PARTS}'s reason — a
     * client reads this string, so renaming a Java identifier must not rename the contract.
     */
    static final String RECORD = "record";

    private final PatientDirectoryService patientDirectoryService;

    public PatientResource(PatientDirectoryService patientDirectoryService) {
        this.patientDirectoryService = patientDirectoryService;
    }

    /**
     * {@code GET /api/patients} : one page of the caller's patient directory.
     *
     * <p><b>Paged since 2026-08-22</b> (web-mobile-port.md § Phase 1.1). It previously accepted no
     * parameters at all and answered with the whole caseload, and its {@code X-Total-Count} was
     * {@code list.size()} — a header that agreed with the body by construction and so could never
     * tell a client there was more. It now carries the number of <em>matches</em>, alongside the
     * {@code Link} header, exactly as {@code ProfileResource} does.
     *
     * <p>The trade this makes explicit: a phone on mobile data receives twenty rows rather than a
     * whole caseload. {@code web/} already asks for {@code size=200} and filters client-side, so it
     * is unaffected; a caller that sends no paging parameters now receives Spring's default page
     * rather than everything, which is the point.
     *
     * <p><b>Two headers name what a caller's discipline cost this read</b>: {@link #RESTRICTED_PARTS},
     * what was withheld from the list, and {@link #RESTRICTED_FOLLOW_UPS}, what the same refusal also
     * blocks behind a row (backlog item 128). Both are absent for a caller who was refused nothing,
     * which is the ordinary case, and neither changes the body.
     *
     * @param pageable standard Spring Data paging. Sorts are whitelisted — see
     *     {@link PatientDirectoryService#sortableProperties()} — because the list is assembled in
     *     memory and a sort is a comparator lookup rather than an index.
     * @param query matched against the patient's name <em>and</em> id, case-insensitively
     * @param sex {@code female} / {@code male} / {@code unspecified}, matching the DTO's closed set
     * @param childrenOnly restricts to patients under 18, computed per read from the birth date
     */
    @GetMapping
    public ResponseEntity<List<PatientListItem>> list(
        @ParameterObject Pageable pageable,
        @RequestParam(required = false) String query,
        @RequestParam(required = false) String sex,
        @RequestParam(required = false) Boolean childrenOnly
    ) {
        PatientDirectoryService.Directory directory;
        try {
            directory = patientDirectoryService.directory(pageable, new PatientDirectoryService.DirectoryFilter(query, sex, childrenOnly));
        } catch (IllegalArgumentException e) {
            // An unsortable property. 400 rather than a silently unsorted page: the latter looks like
            // a backend that lost the clinician's ordering, and nobody reports that as a bug.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(
            ServletUriComponentsBuilder.fromCurrentRequest(),
            directory.page()
        );
        if (!directory.restrictions().isEmpty()) {
            headers.add(
                RESTRICTED_PARTS,
                directory.restrictions().stream().map(PatientDirectoryService.RestrictedPart::token).collect(Collectors.joining(","))
            );
        }
        // Asked of the Directory rather than tested against a constant here, so that the fact and the
        // enum that decides it stay in one place — see RestrictedPart.blocksRecord(). A directory with
        // no restrictions answers false, so the ordinary request pays nothing and gains no header.
        if (directory.blocksEveryRecord()) {
            headers.add(RESTRICTED_FOLLOW_UPS, RECORD);
        }
        return ResponseEntity.ok().headers(headers).body(directory.page().getContent());
    }

    /**
     * {@code POST /api/patients/{id}/activities} : file an activity-log entry.
     *
     * <p><b>New in Phase 1.3, and not a port.</b> {@code web/} has POSTed here since the dashboard
     * was built; the endpoint never existed, so every "add activity" returned 404. See
     * {@code web-mobile-port.md}.
     *
     * <p><b>Left outside the hoisted prefixes on purpose.</b> {@code /api/patients/**} is not listed
     * above the {@code POST /api/**} rule in {@link net.jojoaddison.config.SecurityConfiguration},
     * so this requires {@code CLINICAL_MUTATION} — carer, chemist and technician read a record
     * and cannot file into one. The four prefixes that <em>are</em> hoisted (onboarding, messaging,
     * notifications, absences) were each hoisted to escape that rule, and will look like precedent
     * to the next person. They are not: filing a clinical observation is exactly what the rule is for.
     *
     * <p>404 rather than 403 for a patient outside the caller's caseload, matching the GET: a
     * clinician must not learn that a patient id is real by trying to write to it.
     */
    @PostMapping("/{id}/activities")
    public ResponseEntity<ActivityLogEntry> appendActivity(@PathVariable String id, @RequestBody CreateActivity request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(patientDirectoryService.appendActivity(id, request));
        } catch (PatientDirectoryService.PatientNotInCaseloadException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patient for this clinician", e);
        } catch (IllegalArgumentException e) {
            // A clientRef already spent on a different write. 409 rather than 400: the request is
            // well-formed, it simply cannot be honoured twice.
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    /**
     * {@code POST /api/patients/{id}/reports} : file a clinical report.
     *
     * <p>Same authorization and the same 404 rule as {@link #appendActivity}.
     *
     * <p>This carries <b>metadata only</b> — a name, a category, a URL. It is not a file upload, and
     * the dashboard's "upload" control on the patient record never moved any bytes either; it sent
     * the filename as a URL. Attaching real content to a patient record is a separate feature and is
     * deliberately not pretended at here.
     */
    @PostMapping("/{id}/reports")
    public ResponseEntity<ClinicalReport> appendReport(@PathVariable String id, @RequestBody CreateReport request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(patientDirectoryService.appendReport(id, request));
        } catch (PatientDirectoryService.PatientNotInCaseloadException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patient for this clinician", e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    /**
     * {@code GET /api/patients/{id}/cases} : the patient's open cases.
     *
     * <p>Proxied rather than read from patientservice directly. Its {@code /api/clinical-cases} has no
     * clinician scope — it filters by patient, not by who is asking — so a client calling it receives
     * every case in the estate and narrows the list in the browser. Here the narrowing is server-side,
     * and since backlog item 23 the read behind it is scoped to the patient in the path.
     *
     * <p>Archived cases are excluded, matching the sibling's own default.
     *
     * <p><b>It answers 503 to a discipline hc-patient refuses the case collection, and that is deliberate
     * since backlog item 127.</b> This response <em>is</em> that collection, so there is no partial of it
     * to serve and no header to hang a marker on — an empty page would say "this patient has no cases",
     * which is a clinical claim and a false one. {@link PatientDirectoryService#casesFor} carries the
     * argument and the alternatives that were rejected; what item 127 changed is the <em>sentence</em> such
     * a caller reads, at {@link net.jojoaddison.service.PatientServiceUnavailableException#title()}.
     */
    @GetMapping("/{id}/cases")
    public ResponseEntity<List<CaseSummary>> cases(@PathVariable String id, @ParameterObject Pageable pageable) {
        try {
            Page<CaseSummary> page = patientDirectoryService.casesFor(id, pageable);
            HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(ServletUriComponentsBuilder.fromCurrentRequest(), page);
            return ResponseEntity.ok().headers(headers).body(page.getContent());
        } catch (PatientDirectoryService.PatientNotInCaseloadException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patient for this clinician", e);
        }
    }

    /**
     * {@code PATCH /api/patients/{id}/cases/{caseId}} : edit the clinical fields of a case.
     *
     * <p><b>This route exists for a security reason.</b> patientservice's own PATCH is gated on its
     * {@code requireWrite}, which passes for any authenticated non-patient caller — verified on the
     * deployed stack, where a <em>carer</em> gets 400 rather than 403 from it. So a role that is
     * read-only here can edit a diagnosis by going through the gateway's {@code patientservice} route
     * directly. Through this route it is behind {@code CLINICAL_MUTATION} and the caseload check.
     *
     * <p>Only symptoms, diagnosis, brief and status are forwarded. A whole-document PATCH would let a
     * caller move a case to another patient or reassign it; neither is this screen's job.
     */
    /**
     * {@code GET /api/patients/{id}/cases/{caseId}} : one case in full.
     *
     * <p>Separate from the list because {@code symptoms} and {@code diagnosis} are unbounded free
     * text. Carrying them on every row of a page would put kilobytes of clinical prose on the wire
     * to render a summary line of it — the wrong trade on a phone, and the reason
     * {@link net.jojoaddison.service.dto.PatientDtos.CaseSummary} stays lean.
     *
     * <p><b>This is the one case endpoint that serves an archived case, since 2026-09-11</b> (backlog
     * item 82). The lists above exclude them and still do; a case retired while a clinician had it open
     * renders here rather than 404ing, which is what this method promised from the day it was written
     * and did not do for three weeks. The refusal is unchanged for anyone outside the caseload, and the
     * response carries {@code archivedAt} so a client can say on screen that the case is retired —
     * {@link net.jojoaddison.service.PatientDirectoryService#caseDetail} argues both halves.
     */
    @GetMapping("/{id}/cases/{caseId}")
    public ResponseEntity<CaseDetail> caseDetail(@PathVariable String id, @PathVariable String caseId) {
        try {
            return ResponseEntity.ok(patientDirectoryService.caseDetail(id, caseId));
        } catch (PatientDirectoryService.PatientNotInCaseloadException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PatchMapping("/{id}/cases/{caseId}")
    public CaseSummary updateCase(
        @PathVariable String id,
        @PathVariable String caseId,
        @RequestBody PatientDirectoryService.CaseUpdate changes
    ) {
        try {
            return patientDirectoryService.updateCase(id, caseId, changes);
        } catch (PatientDirectoryService.PatientNotInCaseloadException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such case for this clinician", e);
        }
    }

    /**
     * {@code GET /api/patients/{id}} : one patient's record.
     *
     * <p>404 when the patient is not one of the caller's, which is the same answer as for a patient
     * that does not exist. Distinguishing the two would let any clinician test whether a given
     * patient id is real.
     *
     * <p><b>It carries {@link #RESTRICTED_PARTS} too, since backlog item 112.</b> Item 107 gave a
     * pharmacist and a chemist a directory in which every row was a dead end: this endpoint composes
     * {@code /api/activity-logs}, which hc-patient refuses those two disciplines, and answered 503 for
     * the whole record over one panel of it. It now serves the record without that panel and names it,
     * with the same token and the same vocabulary the list above uses — see
     * {@link PatientDirectoryService#recordWithinScope}, which has the per-discipline measurements and
     * argues why a technician's record still refuses.
     *
     * <p><b>{@code ResponseEntity} rather than the record itself, and the body is byte-identical.</b>
     * A header is the only place a restriction can go: {@code web/} and {@code mobile/} both deserialise
     * this response as the record object, so a wrapper naming the restriction would break them on the
     * release that shipped it — item 111 Decision A's trade, unchanged. Neither frontend reads the header
     * on this endpoint yet; item 114 did that work for the directory alone, and extending it to the
     * record screen is a client-side row rather than something this can close.
     */
    @GetMapping("/{id}")
    public ResponseEntity<PatientRecord> get(@PathVariable String id) {
        PatientDirectoryService.RecordView found = patientDirectoryService
            .recordWithinScope(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patient for this clinician"));
        HttpHeaders headers = new HttpHeaders();
        if (!found.restrictions().isEmpty()) {
            headers.add(
                RESTRICTED_PARTS,
                found.restrictions().stream().map(PatientDirectoryService.RestrictedPart::token).collect(Collectors.joining(","))
            );
        }
        return ResponseEntity.ok().headers(headers).body(found.record());
    }
}
