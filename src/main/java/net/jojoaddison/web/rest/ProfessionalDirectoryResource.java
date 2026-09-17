package net.jojoaddison.web.rest;

import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.service.ProfessionalDirectoryService;
import net.jojoaddison.service.dto.DirectoryRecordDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.jhipster.web.util.ResponseUtil;

/**
 * The outbound directory read: {@code accountId} in, name and role and licence status out
 * (backlog.md item 51).
 *
 * <p>Its one intended caller is another product. hc-admin's professional directory holds link rows
 * that know a clinician exists and can never learn who they are, because the events it consumes
 * carry identifiers only; the fields it needs are spread across three collections here. This is the
 * narrow projection rather than three resources opened wider, which is the same argument hc-admin
 * makes for the {@code GeographicSpace} read it already serves this stack.
 *
 * <h2>The authority rule, and the three that were rejected</h2>
 *
 * <p><b>{@code ROLE_ADMIN}.</b> Not because it is the strictest name available, but because it is
 * the one that admits the caller this endpoint exists for and nothing wider.
 *
 * <p><b>What actually reaches this endpoint, established before the rule was chosen.</b>
 * {@code hc-professional-service} sits on the shared {@code infranet} network, so hc-admin reaches
 * it directly rather than through this stack's gateway, and its {@code ProfessionalServiceClient}
 * already does exactly that today: it <em>relays the calling administrator's own token</em> to
 * {@code POST /api/duty-roster}, which is {@code ROLE_ADMIN} here, and this service honours it. The
 * three products share one signing key, and origin validation is shipped off
 * ({@code application.security.jwt.validate-origin: false}, unset in every deployed environment), so
 * that token verifies here carrying the authorities hc-admin granted it. <b>So an hc-admin
 * administrator arriving with {@code ROLE_ADMIN} is not a hole this rule opens — it is the
 * mechanism the integration already runs on, in production.</b>
 *
 * <p><em>{@code .authenticated()}</em> is the rule the mirror endpoint uses in the other direction:
 * hc-admin's {@code GET /api/professionals/me/**} is authentication and nothing more. <b>That
 * precedent inverts here and the inversion is the whole argument.</b> Those endpoints take no
 * subject — a clinician can only ever read their own shifts, so identity <em>is</em> the boundary
 * and an authority check would constrain nothing. This endpoint takes the subject from the path, so
 * identity constrains nothing at all: {@code .authenticated()} would hand one clinician's name,
 * discipline and credential state to every account in three products, including every patient and
 * every role-less applicant.
 *
 * <p><em>{@code CLINICAL_AND_ADMIN}</em> would keep it inside the estate but open one colleague's
 * identity to every clinician here — including the read-only disciplines — to serve a caller that is
 * none of them. No product surface in this stack calls this endpoint; {@code web/} and {@code
 * mobile/} have their own screens over the same rows. Widening a rule for callers that do not exist
 * is how a projection built for one integration becomes a general-purpose people search.
 *
 * <p><em>Narrower than {@code ROLE_ADMIN}</em> — admitting hc-admin and refusing this stack's own
 * administrators — <b>cannot be built here today</b>, and it is worth saying why rather than
 * leaving it looking unconsidered. It would need the caller's issuer to be load-bearing, and
 * {@code TokenOriginValidator} is off in every environment; enabling it would <em>refuse</em>
 * hc-admin rather than single it out, unless their issuer were added to {@code trusted-issuers},
 * which is a per-environment decision this repository does not own. There is no service account and
 * no machine credential anywhere in the estate. {@code SecurityUtils.getCurrentAccountId()} does
 * discard a {@code uid} minted by another issuer — but that helps the endpoints that resolve
 * <em>the caller</em>, and this one resolves a subject named in the path, so it offers nothing here.
 *
 * <p><b>The residual cost, stated plainly rather than argued away.</b> {@code ROLE_ADMIN} admits
 * this stack's own administrators, and an administrator of hc-patient, for the same reason it admits
 * hc-admin's: one signing key and no enforced issuer check. That is true of every {@code ROLE_ADMIN}
 * surface this service already has — {@code /api/admin/**}, the compliance surface, {@code
 * /management/**}, {@code /v3/api-docs/**} — so it is a property of the estate's shared key rather
 * than something this endpoint introduces. Narrowing it is the same change as closing all of them,
 * which is what turning on origin validation would be.
 *
 * <h2>What hc-admin still has to settle, and it is not this rule</h2>
 *
 * <p><b>Their item 35 plans to call this from a Kafka consumer — "the topic is a trigger, not a
 * source" — and a consumer has no token to relay.</b> Their own item 36 records the precondition as
 * <em>"the relayed token must verify"</em>, which presumes a request in flight; on a backfill from
 * {@code earliest} there is no administrator and no request. That is refused before any authority
 * rule is consulted — a 401, not a 403 — so <b>no choice made here fixes it</b>, and it would be
 * equally unfixed under {@code .authenticated()}. It is theirs to answer: reconcile on an
 * administrator's request, or provision a credential, which the estate has never had. Recorded so
 * the first 401 is not read as this rule being wrong.
 */
@RestController
@RequestMapping("/api/professionals")
@PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
public class ProfessionalDirectoryResource {

    private final Logger log = LoggerFactory.getLogger(ProfessionalDirectoryResource.class);

    private final ProfessionalDirectoryService professionalDirectoryService;

    public ProfessionalDirectoryResource(ProfessionalDirectoryService professionalDirectoryService) {
        this.professionalDirectoryService = professionalDirectoryService;
    }

    /**
     * {@code GET /professionals/:accountId/directory-record} : the composed directory record.
     *
     * <p><b>404 rather than a record with every field missing</b>, which is the distinction the
     * consumer acts on: an empty record invites it to write a row for a clinician this service has
     * never heard of, and a directory row with no name reads as a rendering fault rather than as a
     * bad identifier. The refusal precedes the lookup — {@link PreAuthorize} is evaluated before the
     * method body — so an unauthorised caller cannot use the difference between 403 and 404 to
     * discover which account ids exist.
     *
     * @param accountId the gateway {@code User.id} of the professional to resolve.
     * @return {@code 200} with the record, or {@code 404} if no profile and no application exist for
     *         this account.
     */
    @GetMapping("/{accountId}/directory-record")
    public ResponseEntity<DirectoryRecordDTO> getDirectoryRecord(@PathVariable("accountId") String accountId) {
        log.debug("REST request to get the directory record for account {}", accountId);
        return ResponseUtil.wrapOrNotFound(professionalDirectoryService.directoryRecord(accountId));
    }
}
