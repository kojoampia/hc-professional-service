package net.jojoaddison.web.rest;

import static java.util.stream.Collectors.joining;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.repository.ProfileRepository;
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
 */
@RestController
@RequestMapping("/api/profiles")
public class ProfileResource {

    private final Logger log = LoggerFactory.getLogger(ProfileResource.class);

    private static final String ENTITY_NAME = "professionalMsProfile";

    /**
     * The fields a merge-patch here may not change, and the endpoint that does set each
     * (backlog.md item 60).
     *
     * <h2>Why five refusals and two applications, rather than copying all seven</h2>
     *
     * <p>{@code ProfileService.partialUpdate} copied eleven of {@code Profile}'s fields and stopped,
     * so a patch naming any of the other seven answered 200 with the row unchanged and the
     * unmodified profile as the body — the caller's own read-back confirming a write that never
     * happened. That the split was eleven and seven is an accident, not a design:
     * {@code .jhipster/Profile.json} lists {@code title}, {@code emergencyContact},
     * {@code specialtyCategoryId} and {@code teamIds}, so the generator would have emitted them; the
     * three push preferences were added by hand for MOB9 and were never in the generator's input at
     * all. Nobody decided any of this, which is why the fix had to be a decision rather than a
     * completion.
     *
     * <p><b>{@code title} and {@code emergencyContact} are applied</b>, alongside the eleven.
     * {@code OnboardingService.upsertOwnProfile} writes both from a clinician's own onboarding body,
     * on exactly the same footing as {@code firstName}, {@code address} and {@code cardNumber} —
     * fields this endpoint has always let the same six roles change. Refusing them would expose no
     * less data and would leave no way to correct a title short of a whole-document {@code PUT}.
     *
     * <p><b>The five below are refused, because each already has an owner with narrower authority
     * than this endpoint.</b> {@code PATCH /api/profiles/&#123;id&#125;} is open to all six
     * {@code CLINICAL_MUTATION} roles and carries no ownership check, so it is the <em>weakest</em>
     * write path in the service:
     *
     * <ul>
     *   <li>{@code specialtyCategoryId} and {@code teamIds} are set by
     *       {@code PUT /api/onboarding/applications/&#123;id&#125;/organization}, which is
     *       {@code ROLE_ADMIN} only and appends an {@code OnboardingEvent} as part of the state
     *       machine. Copying them here would give every nurse the power to file any colleague under
     *       any discipline, and would move an assignment that the application's own history could
     *       not then explain.</li>
     *   <li>The three push preferences are set by {@code PUT /api/notifications/preferences}, which
     *       writes the <em>caller's own</em> profile and no one else's. Copying them here would let
     *       one clinician switch off another's compliance notifications — the nudge that says their
     *       licence is about to expire — and the victim would see nothing.</li>
     * </ul>
     *
     * <p><b>Three shapes were rejected.</b> <em>Copy all seven</em> creates those two weaker second
     * writers, and independently loses data: {@code Profile.teamIds} is initialised to an empty list,
     * so a {@code != null} guard of the shape the eleven use fires on every patch and would empty a
     * clinician's teams whenever they changed a phone number. <em>Gate the sensitive five by
     * authority</em> reads well but answers the wrong question — an admin is not the missing
     * ingredient, an {@code OnboardingEvent} and an ownership check are, and both already exist one
     * endpoint over; it would also make the same request succeed or fail depending on who sent it,
     * for fields that have a documented home either way. <em>Delete the {@code PATCH} mapping</em>,
     * as items 56 and 57 did to the deletes it echoes, was the closest call: no client in the estate
     * calls it. But item 60 was opened precisely because a patch is the repair somebody reaches for
     * after reading item 57, and {@code PUT} is a whole-document replace that drops what it omits —
     * removing the mapping would take away the only non-destructive correction this service has.
     */
    private static final Map<String, String> PATCH_REFUSED_FIELDS = Map.of(
        "specialtyCategoryId",
        "PUT /api/onboarding/applications/{id}/organization",
        "teamIds",
        "PUT /api/onboarding/applications/{id}/organization",
        "pushMessagesEnabled",
        "PUT /api/notifications/preferences",
        "pushComplianceEnabled",
        "PUT /api/notifications/preferences",
        "pushShowSenderName",
        "PUT /api/notifications/preferences"
    );

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
     * <p>It names the endpoint that does create one, for the reason {@link #PATCH_REFUSED_FIELDS}
     * gives at length: a refusal that will not say where to go instead is backlog.md item 46 wearing
     * a 400.
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
     * included; it would also need item 60's five-field refusal duplicated onto the create path, and
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
     * {@code address} and {@code emergencyContact}, it predates this change, and it is kept
     * deliberately — {@code OnboardingService.upsertOwnProfile} replaces the same two, so merging
     * here would make the two write paths mean different things by the same request. See
     * {@code ProfileService.partialUpdate}. See {@link #PATCH_REFUSED_FIELDS} and backlog.md
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

        refuseFieldsThisEndpointDoesNotOwn(patch, profile, stored);

        Optional<Profile> result = profileService.partialUpdate(profile);

        return ResponseUtil.wrapOrNotFound(result, HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, profile.getId()));
    }

    /**
     * Refuses, rather than silently ignores, a merge-patch that would change a field this endpoint
     * does not own (backlog.md item 60).
     *
     * <p><b>The refusal is on a change, not on the mention.</b> A client that GETs a profile, edits
     * one field and PATCHes the whole document back names all five of these at their current values,
     * and refusing that would make the honest answer unusable. Carrying a value forward unchanged is
     * not a dropped write, because nothing the caller asked for went missing.
     *
     * <p><b>Not the silence item 46 described, and not its mirror either.</b> There an operator was
     * refused with no reason given; the answer here has to name the field and the endpoint that does
     * own it, or it is the same defect wearing a 400.
     */
    private void refuseFieldsThisEndpointDoesNotOwn(ObjectNode patch, Profile incoming, Profile stored) {
        List<String> refused = new ArrayList<>();
        if (changes(patch, "specialtyCategoryId", incoming.getSpecialtyCategoryId(), stored.getSpecialtyCategoryId())) {
            refused.add("specialtyCategoryId");
        }
        if (patch.has("teamIds") && !Objects.equals(orEmpty(incoming.getTeamIds()), orEmpty(stored.getTeamIds()))) {
            refused.add("teamIds");
        }
        if (changes(patch, "pushMessagesEnabled", incoming.getPushMessagesEnabled(), stored.getPushMessagesEnabled())) {
            refused.add("pushMessagesEnabled");
        }
        if (changes(patch, "pushComplianceEnabled", incoming.getPushComplianceEnabled(), stored.getPushComplianceEnabled())) {
            refused.add("pushComplianceEnabled");
        }
        if (changes(patch, "pushShowSenderName", incoming.getPushShowSenderName(), stored.getPushShowSenderName())) {
            refused.add("pushShowSenderName");
        }
        if (!refused.isEmpty()) {
            String detail = refused.stream().map(field -> field + " is set by " + PATCH_REFUSED_FIELDS.get(field)).collect(joining("; "));
            // ResponseStatusException rather than BadRequestAlertException, and not for consistency
            // with anything — for the only reason that matters here, which is that the caller has to
            // be able to read which field was refused. ExceptionTranslator.customizeProblem
            // overwrites `title` with extractTitle(), and getCustomizedTitle() returns null for
            // everything but MethodArgumentNotValidException, so a BadRequestAlertException's
            // defaultMessage never reaches the client: the body carries "Bad Request" and
            // "error.fieldnotpatchable" and nothing else. A refusal that will not say what it refused
            // is item 46 wearing a 400, which is precisely what this method exists to avoid. That the
            // same silence applies to every other BadRequestAlertException in the service is a wider
            // finding and is recorded under item 60 rather than fixed here.
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "This endpoint does not set these fields, and will not quietly drop them: " + detail
            );
        }
    }

    private static boolean changes(ObjectNode patch, String field, Object incoming, Object stored) {
        return patch.has(field) && !Objects.equals(incoming, stored);
    }

    private static List<String> orEmpty(List<String> ids) {
        return ids == null ? List.of() : ids;
    }

    /**
     * {@code GET  /profiles} : get all the profiles.
     *
     * @param pageable the pagination information.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and the list of profiles in body.
     */
    @GetMapping
    public ResponseEntity<List<Profile>> getAllProfiles(@org.springdoc.core.annotations.ParameterObject Pageable pageable) {
        log.debug("REST request to get a page of Profiles");
        Page<Profile> page = profileService.findAll(pageable);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(ServletUriComponentsBuilder.fromCurrentRequest(), page);
        return ResponseEntity.ok().headers(headers).body(page.getContent());
    }

    /**
     * {@code GET  /profiles/:id} : get the "id" profile.
     *
     * @param id the id of the profile to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the profile, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Profile> getProfile(@PathVariable("id") String id) {
        log.debug("REST request to get Profile : {}", id);
        Optional<Profile> profile = profileService.findOne(id);
        return ResponseUtil.wrapOrNotFound(profile);
    }

    /**
     * {@code GET  /profiles/account/:accountId} : get the "accountId" profile.

     * @param accountId the accountId of the profile to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the profile, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/account/{accountId}")
    public ResponseEntity<Profile> getProfileByAccountId(@PathVariable("accountId") String accountId) {
        log.debug("REST request to get Profile by accountId : {}", accountId);
        Optional<Profile> profile = profileService.findByAccountId(accountId);
        return ResponseUtil.wrapOrNotFound(profile);
    }

    /**
     * {@code GET  /profiles/email/:email} : get the "email" profile.
     *
     * @param email the email of the profile to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the profile, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/email/{email}")
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
