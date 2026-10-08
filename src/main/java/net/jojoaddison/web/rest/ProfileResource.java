package net.jojoaddison.web.rest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.service.ProfileService;
import net.jojoaddison.web.rest.errors.BadRequestAlertException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tech.jhipster.web.util.HeaderUtil;
import tech.jhipster.web.util.PaginationUtil;
import tech.jhipster.web.util.ResponseUtil;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * REST controller for managing {@link net.jojoaddison.domain.Profile}.
 *
 * <h2>Every read here is {@code ROLE_ADMIN} (backlog.md item 143)</h2>
 *
 * <p><b>Because every read here names its subject in the path, and an endpoint that takes a subject
 * cannot be gated by authentication</b> — every caller is authenticated as somebody, and the subject
 * is whoever they ask for. Held at {@code .authenticated()} until 2026-09-17, a carer read a doctor's
 * whole profile through {@code /account/&#123;accountId&#125;} — <b>23 fields</b> when it was measured
 * on the quality stack, including {@code cardNumber}, {@code birthDate}, {@code address},
 * {@code contacts}, {@code mobilePhone}, {@code email} and {@code sex} — and the collection
 * read handed over the clinician directory a page at a time. <b>Do not treat that count as fixed:</b>
 * item 143 recorded 21 and it was 23 by the time the fix landed, because the projection is the whole
 * document and a field added to {@code Profile} is published here the day it is added, with no edit to
 * this class and nothing to review. The blast radius is not one product's: the three gateways share one
 * signing key and not a user store, so every account in hc-admin and hc-patient is authenticated here,
 * and hc-admin dials this service directly over {@code infranet} where the gateway's
 * {@code /services/**} rule never runs.
 *
 * <p><b>The gate that operates is {@code SecurityConfiguration}'s</b> — {@code GET} <i>and</i>
 * {@code HEAD} on {@code /api/profiles} and {@code /api/profiles/**} — because the filter chain is the
 * layer that was letting this through and because a rule on the path covers a read added here later.
 * <b>{@code HEAD} is listed because leaving it off was a real fail-open, caught in review:</b> Spring
 * MVC dispatches a {@code HEAD} to the {@code @GetMapping} handler, and on this resource the oracle
 * answers on the status line alone — a carer's {@code HEAD} of a known address returned 200 and of an
 * unknown one 404, and {@code HEAD} of the collection returned 200 carrying {@code X-Total-Count}. The
 * {@code @PreAuthorize} on each handler is the same requirement made legible where the code is, the
 * way {@code AccountIdMigrationResource} carries one under {@code /api/admin}. Neither is the test:
 * {@code ProfileResourceIT} and {@code ClinicalAuthorityMatrixIT} assert the refusal, and this
 * service's ITs run with the filter chain switched on, so a rule deleted from either layer reddens.
 *
 * <p><b>Writes are unchanged</b> and still admit {@code CLINICAL_MUTATION}'s six. What item 143
 * decided is who may read a profile that is not theirs.
 *
 * <p>⛔ <b>A clinician reading their own profile does not come through here, and no self-exception
 * belongs here.</b> {@code GET /api/onboarding/profile} resolves the caller from the {@code uid}
 * claim and takes no subject at all — {@code mobile/}'s {@code profile-api.service.ts} already calls
 * exactly that, and {@code web/} calls this resource nowhere — so identity really is the boundary
 * there and an authority check would constrain nothing further. Excusing the caller on a
 * subject-addressed path means comparing caller against path, which is the shape that gets it wrong;
 * the endpoint that cannot name anyone else is the one that cannot leak anyone else.
 */
@RestController
@RequestMapping("/api/profiles")
public class ProfileResource {

    private final Logger log = LoggerFactory.getLogger(ProfileResource.class);

    private static final String ENTITY_NAME = "professionalMsProfile";

    /**
     * The fields a merge-patch here may not change, and the endpoint that does set each, now live in
     * {@link ProfileFieldOwnership} — <b>read that class</b>, which carries the whole argument.
     *
     * <h2>Why they moved, which is itself part of the argument (profile.md, T1)</h2>
     *
     * <p>{@code PUT /api/profile} is a second partial-write path into this collection and the rule
     * has to be the same on both. Keeping a copy here and writing a second one there is the shape
     * {@code quality/}'s items 84, 91 and 92 record: a fix applied to one of two correct-looking
     * copies, and the conclusion drawn from them is that <b>two correct-for-now copies is how an
     * estate arrives at one wrong one</b>.
     *
     * <p><b>Three shapes were rejected</b> when the refusals were decided, and they are recorded here
     * rather than beside the map because they are arguments about <em>this</em> endpoint. <em>Copy all
     * seven</em> creates two weaker second writers, and independently loses data:
     * {@code Profile.teamIds} is initialised to an empty list, so a {@code != null} guard of the shape
     * the applied fields use fires on every patch and would empty a clinician's teams whenever they
     * changed a phone number — the same trap {@code Profile.contacts} is deliberately left null to
     * avoid. <em>Gate the sensitive ones by authority</em> reads well but answers the wrong question —
     * an admin is not the missing ingredient, an {@code OnboardingEvent} and an ownership check are,
     * and both already exist one endpoint over; it would also make the same request succeed or fail
     * depending on who sent it, for fields that have a documented home either way. <em>Delete the
     * {@code PATCH} mapping</em>, as items 56 and 57 did to the deletes it echoes, was the closest
     * call: no client in the estate calls it. But item 60 was opened precisely because a patch is the
     * repair somebody reaches for after reading item 57, and {@code PUT} is a whole-document replace
     * that drops what it omits — removing the mapping would take away the only non-destructive
     * correction this service has.
     *
     * @see ProfileFieldOwnership#REFUSED_FIELDS
     */
    @Value("${jhipster.clientApp.name}")
    private String applicationName;

    private final ProfileService profileService;

    private final ProfileRepository profileRepository;

    /**
     * Injected so {@link #partialUpdateProfile} can bind the merge-patch document itself — see the
     * note there on why the raw {@link ObjectNode} has to survive as far as this class.
     *
     * <p><b>Jackson 3 ({@code tools.jackson}), not Jackson 2.</b> Both are on the classpath, but
     * Spring Boot 4 auto-configures Jackson 3 and there is <em>no</em> Jackson 2 {@code ObjectMapper}
     * bean in the application context — the one the generated {@code *ResourceIT}s inject comes from
     * the test-only {@code TestJacksonConfiguration}. Declaring the {@code com.fasterxml} types here
     * compiles, starts, and then answers 500 to every {@code PATCH}, because the request is read by
     * the Jackson 3 converter and handed to a parameter of an unrelated {@code ObjectNode} class.
     */
    private final ObjectMapper objectMapper;

    /**
     * Every write here moves {@code modifiedDate} and {@code lastModifiedBy}, which hc-admin's
     * directory renders, so a save that announced nothing would leave that record stale with nothing
     * failing here. This resource used to call {@code OnboardingService.publishProfileStatus} on
     * three of its handlers to say so; it no longer does, because a table of handlers is what missed
     * the two in backlog.md item 49. {@code ProfileStatusAnnouncer} announces off the save itself.
     *
     * <p><b>No {@code DomainEventPublisher} since backlog.md item 66.</b> Its one use here was the
     * {@code entity.created} the refused {@code POST} published; the create that survives —
     * {@code OnboardingService.upsertOwnProfile} — publishes its own, so no {@code Profile}
     * {@code entity.created} was lost with it. The only one that would have been is the event for a
     * profile belonging to nobody, which no consumer could have placed.
     */
    public ProfileResource(ProfileService profileService, ProfileRepository profileRepository, ObjectMapper objectMapper) {
        this.profileService = profileService;
        this.profileRepository = profileRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * What a caller is told instead of being handed a profile that belongs to nobody
     * (backlog.md item 66).
     *
     * <p>It names the endpoint that does create one, for the reason
     * {@link ProfileFieldOwnership#REFUSED_FIELDS} gives at length: a refusal that will not say where
     * to go instead is backlog.md item 46 wearing a 400.
     */
    static final String CREATE_REFUSAL =
        "A profile is created by the clinician it belongs to, through PUT /api/onboarding/profile. " +
        "This endpoint cannot link one to an account — accountId is read-only over HTTP — so every " +
        "profile it created would belong to nobody, and nothing could ever give it an owner.";

    /**
     * {@code POST /profiles} : refused. <b>Since backlog.md item 54 this endpoint could only ever
     * create a profile that belongs to nobody, and it answered 201 while doing it.</b>
     *
     * <p>Item 54 made {@link Profile#getAccountId() accountId} {@code READ_ONLY} over HTTP to close a
     * live account takeover, and {@code READ_ONLY} applies to a create as well as to an update. So a
     * {@code POST} here returned 201 with {@code accountId: null} — measured on the quality stack,
     * twice — and no later call can repair that: {@code PUT} preserves the stored value (which is
     * null), {@code PATCH} cannot carry the field either, and there is no other writer. The row is
     * unreachable the moment it exists. {@code findByAccountId} is how every ownership check in this
     * service resolves a caller, so nobody owns it, no document, roster, absence or patient
     * directory ever hangs off it, and {@code ProfileStatusAnnouncer} skips it because
     * {@code publishProfileStatus} returns on a null {@code accountId} — the estate is never told it
     * exists either. Three such rows are on the quality box, every one of them a probe; its thirteen
     * seeded profiles predate the hardening and are linked, which is exactly why nothing noticed.
     * A {@code --clean} reload would produce thirteen that are not, and that is the cost item 66
     * records: the quality stack cannot be rebuilt from empty.
     *
     * <p><b>The decision behind the refusal, which is item 66's substance.</b> Three shapes were
     * considered and the two that add service surface were refused. <em>Make {@code accountId}
     * writable on create only</em> hands the six {@code CLINICAL_MUTATION} roles a way to author a
     * colleague's profile before that colleague onboards — the unique sparse index refuses a second
     * row for a login that already has one, but it says nothing about a login that does not yet, and
     * {@code upsertOwnProfile} then adopts whatever was planted, push preferences and organisation
     * included; it would also need item 60's field refusals duplicated onto the create path, and
     * item 50 will turn the identifier the caller types into a {@code User.id}, which is the value
     * item 55 had to gate an enumeration endpoint over. <em>Add an admin link endpoint</em> is
     * narrower and auditable, but the {@code OnboardingEvent} that would make it auditable has
     * nothing to hang on at the only moment anything calls it: the fixture links a profile before the
     * application exists.
     *
     * <p><b>What settled it is that no product surface creates a profile on another's behalf.</b>
     * Established across all seven repos and both sibling stacks: {@code web/} and {@code mobile/}
     * write a profile only through {@code PUT /api/onboarding/profile}, hc-admin and hc-patient only
     * ever {@code GET} this path, and the sole non-test caller in the estate was
     * {@code quality/seed-data.py}. The product's answer to "create a professional on somebody's
     * behalf" is already built and is the gateway's: an administrator creates the <em>account</em>,
     * and the clinician completes their own profile through onboarding, which is also what makes the
     * identity on it something credentialing verifies rather than something an administrator
     * asserted. An endpoint added here would have been an endpoint added for a fixture.
     *
     * <p><b>Refused rather than deleted, which is where this parts company with items 56 and 57.</b>
     * Those removed a {@code DELETE} nobody reaches for, so a 405 costs nobody anything. A
     * {@code POST} on a collection is the first thing every REST client tries — the fixture author
     * tried it, the item 61 author tried it, and both got a 201 that meant nothing — and a 405 sends
     * the next one looking for a different verb or a different path rather than to the endpoint that
     * works.
     *
     * @return never; always throws.
     */
    @PostMapping
    public ResponseEntity<Profile> createProfile() {
        log.debug("REST request to save Profile — refused, see backlog.md item 66");
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CREATE_REFUSAL);
    }

    /**
     * {@code PUT  /profiles/:id} : Updates an existing profile.
     *
     * @param id the id of the profile to save.
     * @param profile the profile to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated profile,
     * or with status {@code 400 (Bad Request)} if the profile is not valid,
     * or with status {@code 500 (Internal Server Error)} if the profile couldn't be updated.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Profile> updateProfile(
        @PathVariable(value = "id", required = false) final String id,
        @RequestBody Profile profile
    ) {
        log.debug("REST request to update Profile : {}, {}", id, profile);
        if (profile.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, profile.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!profileRepository.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        profile = profileService.update(profile);
        return ResponseEntity.ok()
            .headers(HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, profile.getId()))
            .body(profile);
    }

    /**
     * {@code PATCH  /profiles/:id} : Partial updates given fields of an existing profile, field will ignore if it is null
     *
     * <p><b>The body arrives as a JSON document and is bound second, deliberately.</b> A merge-patch
     * is defined over the document, and binding it to a {@link Profile} first destroys the one thing
     * this handler has to know: which fields the caller actually <em>named</em>. {@code teamIds} is
     * the case that proves it — the field is initialised to an empty list, so a bound
     * {@code Profile} is never null there and "the caller sent no teams" and "the caller sent an
     * empty list of teams" are the same value.
     *
     * <p><b>One deviation from RFC 7396, which {@code application/merge-patch+json} names: a nested
     * object is replaced wholesale rather than merged recursively.</b> That is true of
     * {@code address} and {@code contacts}, it predates this change, and it is kept
     * deliberately — {@code OnboardingService.upsertOwnProfile} replaces the same two, so merging
     * here would make the two write paths mean different things by the same request. See
     * {@code ProfileService.partialUpdate}. See {@link ProfileFieldOwnership} and backlog.md
     * item 60.
     *
     * @param id the id of the profile to save.
     * @param patch the merge-patch document.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated profile,
     * or with status {@code 400 (Bad Request)} if the profile is not valid or names a field this
     * endpoint does not own,
     * or with status {@code 404 (Not Found)} if the profile is not found,
     * or with status {@code 500 (Internal Server Error)} if the profile couldn't be updated.
     */
    @PatchMapping(value = "/{id}", consumes = { "application/json", "application/merge-patch+json" })
    public ResponseEntity<Profile> partialUpdateProfile(
        @PathVariable(value = "id", required = false) final String id,
        @RequestBody ObjectNode patch
    ) {
        log.debug("REST request to partial update Profile partially : {}, {}", id, patch);
        Profile profile;
        try {
            profile = objectMapper.treeToValue(patch, Profile.class);
        } catch (JacksonException e) {
            throw new BadRequestAlertException("Unreadable profile document", ENTITY_NAME, "invalidbody");
        }
        if (profile.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, profile.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        Profile stored = profileRepository
            .findById(id)
            .orElseThrow(() -> new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound"));

        ProfileFieldOwnership.refuseFieldsThisEndpointDoesNotOwn(patch, profile, stored);

        Optional<Profile> result = profileService.partialUpdate(profile);

        return ResponseUtil.wrapOrNotFound(result, HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, profile.getId()));
    }

    /**
     * {@code GET  /profiles} : get all the profiles. {@code ROLE_ADMIN} — see the class comment.
     *
     * <p><b>The widest of the four</b>: it names no subject because it returns every one of them. A
     * caller who had to guess an account id to leak a colleague could page the whole directory here
     * instead, which is why gating the one endpoint hc-admin calls would have settled nothing.
     *
     * @param pageable the pagination information.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and the list of profiles in body.
     */
    @GetMapping
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ResponseEntity<List<Profile>> getAllProfiles(@org.springdoc.core.annotations.ParameterObject Pageable pageable) {
        log.debug("REST request to get a page of Profiles");
        Page<Profile> page = profileService.findAll(pageable);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(ServletUriComponentsBuilder.fromCurrentRequest(), page);
        return ResponseEntity.ok().headers(headers).body(page.getContent());
    }

    /**
     * {@code GET  /profiles/:id} : get the "id" profile. {@code ROLE_ADMIN} — see the class comment.
     *
     * @param id the id of the profile to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the profile, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ResponseEntity<Profile> getProfile(@PathVariable("id") String id) {
        log.debug("REST request to get Profile : {}", id);
        Optional<Profile> profile = profileService.findOne(id);
        return ResponseUtil.wrapOrNotFound(profile);
    }

    /**
     * {@code GET  /profiles/account/:accountId} : get the "accountId" profile. {@code ROLE_ADMIN} —
     * see the class comment.
     *
     * <p><b>This is the one the estate is about to point at</b> (backlog.md item 140): the
     * cross-product read contract is {@code GET [product]-service/api/profile/&#123;accountId&#125;},
     * keyed on {@code account.id = Profile.accountId}, and this endpoint is functionally that under
     * a different path. It is also the one measured leaking on the quality stack, by a carer.
     *
     * @param accountId the accountId of the profile to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the profile, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/account/{accountId}")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ResponseEntity<Profile> getProfileByAccountId(@PathVariable("accountId") String accountId) {
        log.debug("REST request to get Profile by accountId : {}", accountId);
        Optional<Profile> profile = profileService.findByAccountId(accountId);
        return ResponseUtil.wrapOrNotFound(profile);
    }

    /**
     * {@code GET  /profiles/email/:email} : get the "email" profile. {@code ROLE_ADMIN} — see the
     * class comment.
     *
     * <p><b>Gated rather than deleted, and it should not survive long</b> (backlog.md item 143). It
     * puts an address in a URL, and therefore in every access log on both sides of the call — the
     * first and most serious of the three reasons the estate's cross-product decision retired the
     * email key in favour of {@code accountId}. Open to any authenticated caller it was additionally
     * an existence oracle on an address — a 200 or a 404 answers "does this person work here"
     * without reading a single field, which is a disclosure the status line makes on its own.
     *
     * <p><b>Why this change gates it instead.</b> Removing a mapping is a contract change and item
     * 143 decided a gate, not a deletion; no caller for it was found in this repository, in
     * {@code web/}, {@code mobile/} or {@code quality/seed-data.py}, and hc-admin's only
     * {@code /api/profiles/email/&#123;email&#125;} constant is on its {@code PatientServiceClient},
     * pointed at {@code patientservice} rather than here — but "no caller I can see" is the reasoning
     * that has to be checked against the repo it is about, and two of the three sibling stacks were
     * read rather than run. Retiring it is its own backlog row, and the admin gate costs that row
     * nothing.
     *
     * @param email the email of the profile to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the profile, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/email/{email}")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ResponseEntity<Profile> getProfileByEmail(@PathVariable("email") String email) {
        log.debug("REST request to get Profile by email : {}", email);
        Optional<Profile> profile = profileService.findByEmail(email);
        return ResponseUtil.wrapOrNotFound(profile);
    }
    /*
     * There is deliberately no DELETE here. It was generated CRUD, no client ever called it, and it
     * did two things wrong at once — backlog item 56.
     *
     * It orphaned six collections. DutyRoster, Absence, Report, PersonalDocument and
     * ProfessionalApplication carry a professionalId or profileId; Team.members is a list of profile
     * ids under a name that shares neither word, which is why a sweep for those two finds five and
     * stops. ProfileService.delete was a bare deleteById with no cascade, so a clinician's roster,
     * absences, reports, documents, application and team membership all survived the clinician.
     *
     * And it announced nothing. A delete is the one change ProfileStatus cannot express — its seven
     * fields are contracted with hc-admin and none can say "gone" — so hc-admin's directory would have
     * gone on rendering a clinician who no longer existed. ProfileStatusAnnouncer says nothing here on
     * purpose; republishing the last known state would assert the opposite of what happened.
     *
     * If a professional ever genuinely needs removing, it comes back as a deliberate change: a soft
     * delete announces through the existing mechanism unchanged, because a soft delete is a save.
     */
}
