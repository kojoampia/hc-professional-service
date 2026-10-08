package net.jojoaddison.web.rest;

import net.jojoaddison.domain.Profile;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.ProfileService;
import net.jojoaddison.web.rest.errors.BadRequestAlertException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The caller's own {@link Profile} — {@code GET} and {@code PUT /api/profile} (profile.md step 2, T1).
 *
 * <h2>⛔ Its own class, and not for tidiness</h2>
 *
 * <p>Two reasons, either of which would be sufficient. The mechanical one: {@link ProfileResource} is
 * {@code @RequestMapping("/api/profiles")} and a class-level prefix cannot be opted out of per
 * handler, so the singular path cannot live there at all. The one that matters: <b>those four reads
 * are {@code ROLE_ADMIN} and these two must be {@code .authenticated()}</b>, and one class holding
 * two answers to "who may call this" is the shape {@code api/} PR #55 exists because of — four GETs
 * in {@code ProfileResource} once carried no annotation, fell through to
 * {@code /api/** → .authenticated()}, and a {@code carer} read a doctor's 23-field profile including
 * {@code cardNumber}, {@code birthDate} and {@code address}.
 *
 * <h2>Why {@code .authenticated()} is the correct gate here, when it was the defect there</h2>
 *
 * <p>The estate's rule (workspace {@code CLAUDE.md} § "How products read each other", 2026-09-17) is
 * that <b>the subject decides the gate, not the resource</b>: an endpoint that names a subject is
 * {@code ROLE_ADMIN}; an endpoint that cannot name anyone but the caller needs only authentication.
 * This one takes no subject at all — the account comes from the {@code uid} claim through
 * {@link SecurityUtils#getCurrentAccountId()} — so an authority check would constrain nothing
 * further, while the admin gate would lock out the people the endpoint is <em>for</em>. An applicant
 * holds {@code ROLE_USER} and nothing else until an administrator assigns one.
 *
 * <p>⛔ <b>So do not answer "a clinician needs their own profile" by excusing the caller on
 * {@code /api/profiles/account/&#123;accountId&#125;}.</b> That shape has to compare caller against
 * path, and it is the one that gets it wrong. The endpoint that cannot name anyone else is the one
 * that cannot leak anyone else, and this is that endpoint.
 *
 * <h2>The security rules this needs, in two repositories (T0)</h2>
 *
 * <p>{@code api/SecurityConfiguration} carries {@code /api/profile} in the island beside
 * {@code /api/onboarding/**}, and {@code gateway/SecurityConfiguration} mirrors it on
 * {@code /services/professionalservice/api/profile}. <b>Both are required and neither is
 * sufficient.</b> Without the service rule the {@code PUT} falls through to
 * {@code PUT /api/** → CLINICAL_MUTATION} and every applicant is 403'd on their own profile; without
 * the gateway rule the request is refused at {@code /services/** → CLINICAL_AND_ADMIN} before the
 * service is reached, <em>and the refusal is attributed to the service</em>.
 *
 * <p>⚠ <b>{@code /api/profile} does not match {@code /api/profiles}</b>, which is the whole reason
 * this path is safe to add: those rules are the literal {@code "/api/profiles"} plus
 * {@code "/api/profiles/**"}, so the singular path inherits the catch-all rather than the admin read
 * gate — and, read the other way, a rule written here cannot widen the plural admin surface.
 * {@code ClinicalAuthorityMatrixIT} asserts both halves rather than leaving them to be read off two
 * configuration files.
 *
 * <p>⚠ <b>The matchers are method-agnostic on purpose, so {@code HEAD} is covered.</b> Spring MVC
 * dispatches a {@code HEAD} to the {@code @GetMapping} handler, and a rule scoped to
 * {@code HttpMethod.GET} lets it fall through to whatever sits below — which on
 * {@code ProfileResource} was a measured fail-open, caught in review. It is not an existence oracle
 * here, because the path names nobody; what a method-scoped rule would still do is answer the same
 * request under a <em>different</em> authority depending on the verb, which is not a thing anyone
 * should have to reason about.
 *
 * <p><b>The {@code @PreAuthorize} below is the second layer</b> and both are kept, as PR #55
 * settled: method security intercepts the invocation whatever the verb, and the filter chain covers
 * a handler added here later. ⛔ Do not delete either on the strength of the other's comment — that
 * is exactly the mistake PR #55's near-miss recorded.
 *
 * <h2>What this resource is not</h2>
 *
 * <p>⛔ <b>It does not retire {@code PUT /api/onboarding/profile}.</b> That goes with the rest of
 * {@code /api/onboarding/**} in T3, and {@code web/} is re-pointed in T6 — so two writers coexist for
 * now, which is precisely why this one must not inherit the other's unconditional thirteen-field
 * write. See {@code ProfileService.partialUpdateOwnProfile}.
 */
@RestController
@RequestMapping("/api/profile")
public class OwnProfileResource {

    private final Logger log = LoggerFactory.getLogger(OwnProfileResource.class);

    private static final String ENTITY_NAME = "professionalMsProfile";

    private final ProfileService profileService;

    /**
     * Injected so {@link #updateOwnProfile} can bind the request document itself.
     *
     * <p><b>The body arrives as a JSON document and is bound second, for the same reason
     * {@code ProfileResource.partialUpdateProfile} does it</b> (backlog.md item 60): a partial write
     * is defined over the document, and binding it to a {@link Profile} first destroys the one thing
     * the refusal has to know — which fields the caller actually <em>named</em>. {@code teamIds} is
     * the case that proves it: the field is initialised to an empty list, so on a bound
     * {@code Profile} "the caller sent no teams" and "the caller sent an empty list of teams" are the
     * same value, and only the raw node tells them apart.
     *
     * <p><b>Jackson 3 ({@code tools.jackson}), not Jackson 2.</b> Both are on the classpath, but
     * Spring Boot 4 auto-configures Jackson 3 and there is <em>no</em> Jackson 2 {@code ObjectMapper}
     * bean in the application context. Declaring the {@code com.fasterxml} types here compiles,
     * starts, and then answers 500 to every write, because the request is read by the Jackson 3
     * converter and handed to a parameter of an unrelated {@code ObjectNode} class.
     */
    private final ObjectMapper objectMapper;

    public OwnProfileResource(ProfileService profileService, ObjectMapper objectMapper) {
        this.profileService = profileService;
        this.objectMapper = objectMapper;
    }

    /**
     * {@code GET /api/profile} : the caller's own profile.
     *
     * <p><b>404 when the caller has none yet</b>, which is most of an applicant's time on the wizard.
     * That follows {@code GET /api/onboarding/profile} and {@code getOwnApplication}, which both
     * throw {@code NOT_FOUND} for the same state — and there is a client-side precedent for treating
     * it as a state rather than an error: {@code web/}'s {@code error-handler.interceptor.ts} already
     * opts the own-application read out of the global error banner <em>because</em> an untreated 404
     * was visible in the deployed portal. An empty {@code 200} was considered and refused: it makes
     * "no profile yet" and "a profile with nothing in it" the same answer, and step 3 keys off
     * {@code Profile.id}.
     *
     * <p>{@code status} is <b>readable</b> here — {@code profile.md} renders it in the page header —
     * and is refused on the write. Those are two different questions about one field and
     * {@link ProfileFieldOwnership} answers the second.
     *
     * @return the caller's profile.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Profile getOwnProfile() {
        String accountId = currentAccountId();
        log.debug("REST request to get own Profile for account : {}", accountId);
        return profileService
            .findByAccountId(accountId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No profile for this account yet"));
    }

    /**
     * {@code PUT /api/profile} : writes the fields the caller named onto the caller's own profile,
     * creating it if there is none.
     *
     * <h2>A partial write behind a {@code PUT}, which is deliberate and is profile.md's doing</h2>
     *
     * <p>The specification names {@code PUT} and specifies <b>a dialog panel per step</b>. Those two
     * together make a whole-document replace wrong by construction: a pane that saves only its own
     * slice would blank every field the other panes wrote, answering 200 with a body confirming the
     * loss. So the verb is the specification's and the semantics are the only ones that can serve it.
     * {@code ProfileService.partialUpdateOwnProfile} carries the rest of that argument.
     *
     * <p><b>{@code accountId} is forced to the caller and never taken from the body</b>, twice over:
     * the field is {@code @JsonProperty(READ_ONLY)} so Jackson drops it before this handler sees it,
     * and the service sets it from the token regardless. A {@code PUT} from account A naming account
     * B cannot reach B's row — there is no path by which the body selects a row at all.
     *
     * <p><b>The one-writer-per-field rule holds here exactly as it does on the {@code PATCH}</b>, and
     * {@code status} is why it is not weaker for being own-scoped: "it is my own row" is an argument
     * about disclosure, and an applicant who could write {@code status} would approve their own
     * credential review. The refusal names the field and the endpoint that does own it, in the same
     * message shape, from the same map — {@link ProfileFieldOwnership}, shared rather than copied.
     *
     * @param document the fields to write; anything absent is left as stored.
     * @return the persisted profile.
     */
    @PutMapping
    @PreAuthorize("isAuthenticated()")
    public Profile updateOwnProfile(@RequestBody ObjectNode document) {
        String accountId = currentAccountId();
        log.debug("REST request to update own Profile for account : {}", accountId);
        Profile incoming;
        try {
            incoming = objectMapper.treeToValue(document, Profile.class);
        } catch (JacksonException e) {
            throw new BadRequestAlertException("Unreadable profile document", ENTITY_NAME, "invalidbody");
        }
        // Against the caller's own stored row, or against nothing at all on the first save — in which
        // case any value the caller named is an introduction and therefore a change, and is refused.
        // That is the right way round: a profile being created is the one moment at which somebody
        // could plant a `status` the state machine never issued.
        Profile stored = profileService.findByAccountId(accountId).orElse(null);
        ProfileFieldOwnership.refuseFieldsThisEndpointDoesNotOwn(document, incoming, stored);

        return profileService.partialUpdateOwnProfile(accountId, incoming);
    }

    /**
     * The caller's gateway {@code User.id} — what {@code Profile.accountId} holds and is looked up by
     * (backlog.md item 50).
     *
     * <p>Spelled out here rather than shared with {@code OnboardingResource}'s identical accessor
     * because the two resources are on opposite sides of T3: that class and its helpers go away, and
     * reaching into it for this would make a retirement into a refactor. {@code SecurityUtils} is the
     * shared part, and it is where the rule actually lives — the {@code uid} claim, discarded when
     * minted by any other issuer, with deliberately no fallback to the login.
     */
    private String currentAccountId() {
        return SecurityUtils.getCurrentAccountId()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
    }
}
