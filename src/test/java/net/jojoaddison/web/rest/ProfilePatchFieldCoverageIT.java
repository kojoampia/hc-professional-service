package net.jojoaddison.web.rest;

import static net.jojoaddison.security.WithMockGatewayUser.Factory.gatewayUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Address;
import net.jojoaddison.domain.EmergencyContact;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A {@code PATCH /api/profiles/&#123;id&#125;} either applies a field or refuses it — it never says
 * 200 and writes nothing (backlog.md item 60).
 *
 * <p><b>The defect.</b> {@code ProfileService.partialUpdate} copied eleven of {@code Profile}'s
 * fields and stopped, so a merge-patch naming {@code title}, {@code emergencyContact},
 * {@code specialtyCategoryId}, {@code teamIds} or any of the three push preferences returned
 * {@code 200} with the row unchanged <em>and the unmodified profile as the body</em> — so the
 * caller's own read-back confirmed a write that never happened. Nothing threw and nothing logged.
 * Reproduced on the quality stack on 2026-09-09 with a positive control in the same request:
 * {@code firstName} changed, {@code title} stayed null, one 200.
 *
 * <p><b>Why some are applied and some refused, rather than all-copy or all-refuse.</b> They are not
 * alike, and the argument is in {@code ProfileResource.PATCH_REFUSED_FIELDS} — read that map rather
 * than any count written here, which is how {@code status} found this class's prose saying "2 / 5".
 * In short: {@code title} and {@code emergencyContact} are the clinician's own data, written by
 * {@code OnboardingService.upsertOwnProfile} on exactly the same footing as the eleven already
 * copied; every refused field has an owning endpoint with narrower authority than this one, and
 * copying it here would create a second writer that skips that endpoint's checks — a 400 is the only
 * answer that neither drops the write nor performs it unchecked.
 *
 * <p><b>The last test is the one that will still be right next year.</b> The named tests pin today's
 * decisions one by one; {@link #everyProfileFieldIsEitherAppliedOrRefused} reflects over {@code Profile}
 * and requires an answer for <em>every</em> field, so a field added later — or restored by a
 * regeneration, since {@code .jhipster/Profile.json} lists all of them and would re-emit them into
 * {@code partialUpdate} — fails here until somebody decides which side it falls on. (It listed
 * <em>four</em> of the original seven until backlog.md item 21 added the three push preferences that
 * file had been missing since MOB9, and {@code status} is in it too, so the regeneration hazard named
 * here keeps growing rather than shrinking.) <b>It has now done its job once:</b> {@code status} was
 * added to {@code Profile} with no writer anywhere in the service and no decision attached, and this
 * test is what refused to let that reach the HTTP surface undecided. That is the
 * same reasoning as {@code TechnicalStructureTest.locationHeadersAreBuiltFromTheRequest}: a rule that
 * needs no maintained list cannot be outgrown by a list nobody updated.
 *
 * <p>Run as {@code ROLE_DOCTOR}, one of the six {@code CLINICAL_MUTATION} roles the blanket
 * {@code PATCH /api/**} rule admits, so every refusal below is this endpoint's own policy and not the
 * mutation matrix answering first.
 */
@AutoConfigureMockMvc
@IntegrationTest
@WithMockGatewayUser(authorities = { "ROLE_DOCTOR" })
class ProfilePatchFieldCoverageIT {

    private static final String ENTITY_API_URL_ID = "/api/profiles/{id}";

    /**
     * Fields no merge-patch is expected to reach, each for a reason that predates this item.
     *
     * <p>{@code id} is the path key. {@code accountId} is {@code READ_ONLY} over HTTP — it became so
     * in item 54, where it was a live account takeover — so a caller cannot send it and
     * {@link #everyProfileFieldIsEitherAppliedOrRefused} would otherwise demand this endpoint make it
     * writable. The last three are audit fields written by Mongo auditing.
     *
     * <p>{@code accountUid} was a fifth entry until item 50 deleted the field; the reflection below
     * enumerates the entity, so it left this list by itself.
     */
    private static final Set<String> NOT_A_PATCHABLE_FIELD = Set.of("id", "accountId", "createdDate", "modifiedDate", "lastModifiedBy");

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private ObjectMapper om;

    @AfterEach
    void cleanup() {
        profileRepository.deleteAll();
    }

    private Profile storedClinician() {
        return profileRepository.save(new Profile().accountId("item60-clinician").firstName("Ama").lastName("Boateng"));
    }

    private org.springframework.test.web.servlet.ResultActions patchWith(String id, String json) throws Exception {
        return restMockMvc.perform(
            patch(ENTITY_API_URL_ID, id).contentType("application/merge-patch+json").accept(MediaType.APPLICATION_JSON).content(json)
        );
    }

    // ---------------------------------------------------------------------------------------------
    // The two that are applied
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code title} is ordinary profile data and is now written like the eleven beside it.
     *
     * <p>It is the clinician's own — {@code upsertOwnProfile} sets it from the onboarding wizard's
     * body — and there is no second endpoint that owns it, so refusing it would leave no way to
     * correct a title at all short of a whole-document {@code PUT}.
     */
    @Test
    void titleIsAppliedRatherThanDropped() throws Exception {
        Profile stored = storedClinician();

        patchWith(stored.getId(), "{\"id\":\"" + stored.getId() + "\",\"title\":\"Prof\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Prof"));

        assertThat(profileRepository.findById(stored.getId()).orElseThrow().getTitle()).isEqualTo("Prof");
    }

    /**
     * {@code contacts} is applied, and applied <b>whole</b>.
     *
     * <p>Replace rather than recursive merge, which RFC 7396 — the media type this endpoint consumes
     * — asks for on a nested object. The deviation is deliberate and is <em>not</em> justified by
     * impossibility: {@code ProfileResource} holds the raw node, so a merge could be reconstructed
     * there. It is not, because {@code address} — the other embedded object the copy chain handles —
     * has always replaced wholesale, and so does {@code upsertOwnProfile} with this very field; a
     * merge here would make the write paths disagree about what writing a next of kin means.
     *
     * <p><b>The list makes that argument sharper rather than weaker</b> (profile.md T1): a
     * per-element merge would have to answer "which element", and on a collection whose members
     * carry no id and no stable order that question has no answer a client could predict.
     */
    @Test
    void contactsAreAppliedRatherThanDropped() throws Exception {
        Profile stored = storedClinician();

        patchWith(
            stored.getId(),
            "{\"id\":\"" +
            stored.getId() +
            "\",\"contacts\":[{\"name\":\"Efua Mensah\",\"relationship\":\"sister\"," +
            "\"phone\":\"+233200000000\"}]}"
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts[0].name").value("Efua Mensah"));

        List<EmergencyContact> saved = profileRepository.findById(stored.getId()).orElseThrow().getContacts();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getName()).isEqualTo("Efua Mensah");
        assertThat(saved.get(0).getRelationship()).isEqualTo("sister");
        assertThat(saved.get(0).getPhone()).isEqualTo("+233200000000");
    }

    /**
     * More than one, which is the whole point of the field being a list — {@code profile.md} requires
     * at least two emergency contacts and the singular field it replaced could express one.
     */
    @Test
    void severalContactsAreAllStored() throws Exception {
        Profile stored = storedClinician();

        patchWith(
            stored.getId(),
            "{\"id\":\"" +
            stored.getId() +
            "\",\"contacts\":[{\"name\":\"Efua Mensah\",\"relationship\":\"sister\",\"phone\":\"+233200000000\"}," +
            "{\"name\":\"Kojo Mensah\",\"relationship\":\"brother\",\"phone\":\"+233200000001\"}]}"
        ).andExpect(status().isOk());

        List<EmergencyContact> saved = profileRepository.findById(stored.getId()).orElseThrow().getContacts();
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(EmergencyContact::getName).containsExactly("Efua Mensah", "Kojo Mensah");
    }

    /**
     * {@code EmergencyContact.address} is a structured {@link Address} since profile.md's T1, and it
     * round-trips through the nesting rather than being flattened or dropped.
     */
    @Test
    void aContactAddressRoundTripsAsAStructuredAddress() throws Exception {
        Profile stored = storedClinician();

        patchWith(
            stored.getId(),
            "{\"id\":\"" +
            stored.getId() +
            "\",\"contacts\":[{\"name\":\"Efua Mensah\",\"address\":{\"streetAddress\":\"12 Oxford St\"," +
            "\"city\":\"Accra\",\"country\":\"Ghana\"}}]}"
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts[0].address.streetAddress").value("12 Oxford St"));

        Address saved = profileRepository.findById(stored.getId()).orElseThrow().getContacts().get(0).getAddress();
        assertThat(saved).isNotNull();
        assertThat(saved.getStreetAddress()).isEqualTo("12 Oxford St");
        assertThat(saved.getCity()).isEqualTo("Accra");
        assertThat(saved.getCountry()).isEqualTo("Ghana");
    }

    /**
     * A second {@code contacts} write replaces the stored list rather than merging into or appending
     * to it.
     *
     * <p>Stated separately because "applied" and "applied whole" are different claims and only the
     * second one rules out a future recursive merge — or an append — being added without a decision.
     * Append is the specific wrong answer a list invites: a wizard pane that re-saved would then
     * double the clinician's contacts on every visit.
     */
    @Test
    void aSecondContactsWriteReplacesTheStoredListWholesale() throws Exception {
        Profile stored = storedClinician();
        stored.setContacts(List.of(new EmergencyContact().name("Efua Mensah").relationship("sister").phone("+233200000000")));
        profileRepository.save(stored);

        patchWith(stored.getId(), "{\"id\":\"" + stored.getId() + "\",\"contacts\":[{\"name\":\"Kojo Mensah\"}]}").andExpect(
            status().isOk()
        );

        List<EmergencyContact> saved = profileRepository.findById(stored.getId()).orElseThrow().getContacts();
        assertThat(saved).as("a replace, not an append — the old contact must not survive beside the new one").hasSize(1);
        assertThat(saved.get(0).getName()).isEqualTo("Kojo Mensah");
        assertThat(saved.get(0).getRelationship()).as("a replace, not a merge — the old relationship must not survive").isNull();
        assertThat(saved.get(0).getPhone()).as("a replace, not a merge — the old phone must not survive").isNull();
    }

    /**
     * ⚠ <b>The retired {@code emergencyContact} name still works on the wire, and that is load-bearing
     * rather than legacy clutter.</b>
     *
     * <p>{@code PUT /api/onboarding/profile} is gone (F8) and both clients were re-pointed at
     * {@code PUT /api/profile} in the same unit — but <b>the alias is still what they send</b>:
     * {@code web/}'s wire type and {@code mobile/}'s {@code me.page.ts} both name
     * {@code emergencyContact}, and dropping it is T6's work, not the path migration's. Deleting the
     * alias now would leave the mobile Me tab answering 200 with the next of kin quietly not saved
     * — the same outcome under a new URL.
     *
     * <p>It is a projection of {@code contacts}, never a second stored field: the write below must
     * land in the list, and the read must come back out of it. Retire the pair with T6, and only once
     * {@code mobile/} has a task that moves it too.
     */
    @Test
    void theRetiredSingularNameStillWritesIntoTheList() throws Exception {
        Profile stored = storedClinician();

        patchWith(
            stored.getId(),
            "{\"id\":\"" + stored.getId() + "\",\"emergencyContact\":{\"name\":\"Efua Mensah\",\"relationship\":\"sister\"}}"
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts[0].name").value("Efua Mensah"))
            .andExpect(jsonPath("$.emergencyContact.name").value("Efua Mensah"));

        List<EmergencyContact> saved = profileRepository.findById(stored.getId()).orElseThrow().getContacts();
        assertThat(saved).as("the alias writes one contact into the list, not a second stored field").hasSize(1);
        assertThat(saved.get(0).getRelationship()).isEqualTo("sister");
    }

    /**
     * And when a body carries both names, {@code contacts} wins — whichever order Jackson binds them
     * in.
     *
     * <p>Asserted because the alternative is a contract nobody could rely on: a client round-tripping
     * a document it had just read sends both, and an outcome that depended on field order in the JSON
     * would be undiagnosable. Both orderings are sent here for exactly that reason — one of them
     * passes on the setter's guard and the other on the binding order, and only testing both says
     * which.
     */
    @Test
    void whenABodyCarriesBothNamesTheListWins() throws Exception {
        Profile first = storedClinician();
        patchWith(
            first.getId(),
            "{\"id\":\"" + first.getId() + "\",\"contacts\":[{\"name\":\"From contacts\"}],\"emergencyContact\":{\"name\":\"From alias\"}}"
        ).andExpect(status().isOk());
        assertThat(profileRepository.findById(first.getId()).orElseThrow().getContacts())
            .extracting(EmergencyContact::getName)
            .containsExactly("From contacts");
        profileRepository.deleteAll();

        Profile second = storedClinician();
        patchWith(
            second.getId(),
            "{\"id\":\"" + second.getId() + "\",\"emergencyContact\":{\"name\":\"From alias\"},\"contacts\":[{\"name\":\"From contacts\"}]}"
        ).andExpect(status().isOk());
        assertThat(profileRepository.findById(second.getId()).orElseThrow().getContacts())
            .extracting(EmergencyContact::getName)
            .containsExactly("From contacts");
    }

    // ---------------------------------------------------------------------------------------------
    // The refused ones, one test each
    // ---------------------------------------------------------------------------------------------

    /**
     * Asserts the refusal, its status code, that the offending field <em>and the endpoint that does
     * own it</em> are named in the body, and that nothing was written. A bare "not 200" would pass
     * against a 500.
     *
     * <p><b>{@code owningEndpoint} is the half that has to be passed in, and it is not decoration.</b>
     * The field name in the response comes from the {@code field +} concatenation in
     * {@code ProfileResource.refuseFieldsThisEndpointDoesNotOwn}, not from
     * {@code PATCH_REFUSED_FIELDS} — so asserting the field name alone leaves the map and the five
     * {@code if} blocks free to drift apart in silence. Deleting an entry from the map while leaving
     * its guard in place still refuses, still says the field's name, and renders "…is set by null":
     * a refusal that has forgotten where to send the caller, which is the degradation this whole
     * change exists to prevent. Asserting the endpoint string closes that direction, because the
     * endpoint can only come from the map.
     *
     * <p><b>What it still does not cover</b> is the mirror: a map entry naming a field that has no
     * guard beside it. That is a misleading constant rather than a wrong response, and closing it
     * properly means restructuring so the loop iterates the map with each predicate beside its
     * endpoint string. Recorded in backlog.md item 60 rather than done here.
     */
    private void assertRefused(String field, String owningEndpoint, String json) throws Exception {
        Profile stored = storedClinician();
        String body = patchWith(stored.getId(), json.replace("__ID__", stored.getId()))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(body).as("the refusal must name the field it refused, or it is item 46 again").contains(field);
        assertThat(body)
            .as("and must name where the caller should go instead — a stale PATCH_REFUSED_FIELDS entry renders 'is set by null'")
            .contains(owningEndpoint);
        assertThat(profileRepository.findById(stored.getId()).orElseThrow().getFirstName())
            .as("a refused patch writes nothing at all, not even the fields it was allowed to write")
            .isEqualTo("Ama");
    }

    /** The endpoint that owns the organisation assignment, per {@code PATCH_REFUSED_FIELDS}. */
    private static final String ORGANISATION_ENDPOINT = "PUT /api/professional-application/{id}/organization";

    /** The endpoint that owns the push preferences, per {@code PATCH_REFUSED_FIELDS}. */
    private static final String PREFERENCES_ENDPOINT = "PUT /api/notifications/preferences";

    /**
     * The owner of {@code status}, per {@code PATCH_REFUSED_FIELDS}. It names one transition and
     * gestures at the rest because there is no single endpoint: {@code /decide},
     * {@code /organization}, {@code /authority-assigned}, {@code /roster-configured},
     * {@code /activate}, {@code /suspend} and {@code /deactivate} each move the status, and all seven
     * are {@code ROLE_ADMIN}.
     */
    private static final String ONBOARDING_TRANSITION_ENDPOINT = "PUT /api/professional-application/{id}/decide";

    /**
     * The specialty is assigned by {@code PUT /api/professional-application/&#123;id&#125;/organization},
     * which is {@code ROLE_ADMIN} only and appends an {@code OnboardingEvent}. This endpoint is open
     * to six roles and appends nothing, so copying the field here would be a strictly weaker second
     * writer whose result the application's own history would not explain.
     */
    @Test
    void specialtyCategoryIdIsRefusedRatherThanDropped() throws Exception {
        assertRefused("specialtyCategoryId", ORGANISATION_ENDPOINT, "{\"id\":\"__ID__\",\"specialtyCategoryId\":\"some-category\"}");
    }

    /** Same owner, same argument, and additionally see {@link #anAbsentTeamIdsIsNotAChange}. */
    @Test
    void teamIdsAreRefusedRatherThanDropped() throws Exception {
        assertRefused("teamIds", ORGANISATION_ENDPOINT, "{\"id\":\"__ID__\",\"teamIds\":[\"some-team\"]}");
    }

    /**
     * The three push preferences are owned by {@code PUT /api/notifications/preferences}, which
     * writes the <em>caller's own</em> profile and nobody else's. This endpoint has no ownership
     * check at all, so copying them here would let any of six roles silence a colleague's
     * compliance nudges — the notification that tells them their licence is expiring.
     */
    @Test
    void pushMessagesEnabledIsRefusedRatherThanDropped() throws Exception {
        assertRefused("pushMessagesEnabled", PREFERENCES_ENDPOINT, "{\"id\":\"__ID__\",\"pushMessagesEnabled\":false}");
    }

    @Test
    void pushComplianceEnabledIsRefusedRatherThanDropped() throws Exception {
        assertRefused("pushComplianceEnabled", PREFERENCES_ENDPOINT, "{\"id\":\"__ID__\",\"pushComplianceEnabled\":false}");
    }

    @Test
    void pushShowSenderNameIsRefusedRatherThanDropped() throws Exception {
        assertRefused("pushShowSenderName", PREFERENCES_ENDPOINT, "{\"id\":\"__ID__\",\"pushShowSenderName\":true}");
    }

    /**
     * {@code status} is the state machine's own field, and this endpoint is not the state machine.
     *
     * <p>Every legal move between {@code ProfileStatus} values is a {@code ROLE_ADMIN}
     * {@code PUT /api/professional-application/&#123;id&#125;/**} that checks
     * {@code OnboardingService.LEGAL_TRANSITIONS} and appends an {@code OnboardingEvent}. The probe
     * below is deliberately a value no transition could reach from a fresh profile: applying it here
     * would write an approval with no credential review before it and no event recording either,
     * which is the one outcome the server-side machine exists to make impossible.
     */
    @Test
    void statusIsRefusedRatherThanDropped() throws Exception {
        assertRefused("status", ONBOARDING_TRANSITION_ENDPOINT, "{\"id\":\"__ID__\",\"status\":\"APPROVED\"}");
    }

    // ---------------------------------------------------------------------------------------------
    // Controls
    // ---------------------------------------------------------------------------------------------

    /**
     * The positive control, without which every test above would pass against a method that refuses
     * everything.
     */
    @Test
    void firstNameIsStillApplied() throws Exception {
        Profile stored = storedClinician();

        patchWith(stored.getId(), "{\"id\":\"" + stored.getId() + "\",\"firstName\":\"Adwoa\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.firstName").value("Adwoa"));

        assertThat(profileRepository.findById(stored.getId()).orElseThrow().getFirstName()).isEqualTo("Adwoa");
    }

    /**
     * A read-modify-write client is not punished for carrying the protected fields back unchanged.
     *
     * <p>The refusal is on a <em>change</em>, not on the mention. A client that GETs a profile,
     * edits one field and PATCHes the whole document names all five protected fields at their
     * current values, and refusing that would make the honest answer unusable. This is not the
     * silence item 60 filed: nothing the caller asked for was dropped, because the caller asked for
     * no change.
     */
    @Test
    void aRoundTrippedDocumentThatChangesNothingProtectedIsNotRefused() throws Exception {
        Profile stored = storedClinician();
        stored.setSpecialtyCategoryId("cat-1");
        stored.setTeamIds(List.of("team-1"));
        stored.setPushMessagesEnabled(Boolean.FALSE);
        profileRepository.save(stored);

        // The read is an administrator's since backlog.md item 143 — GET /api/profiles/** takes its
        // subject from the path and is ROLE_ADMIN — while the PATCH below stays this class's doctor,
        // because the mutation matrix is unchanged. Kept as a real HTTP read rather than serialising
        // the stored document: what this test is about is a client round-tripping the body the
        // service handed it, and a body built in the test is not that body.
        JsonNode readBack = om.readTree(
            restMockMvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(ENTITY_API_URL_ID, stored.getId()).with(
                        gatewayUser("item143.admin", "ROLE_ADMIN")
                    )
                )
                .andReturn()
                .getResponse()
                .getContentAsString()
        );
        String roundTrip = ((com.fasterxml.jackson.databind.node.ObjectNode) readBack).put("firstName", "Adwoa").toString();

        patchWith(stored.getId(), roundTrip).andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value("Adwoa"));

        Profile after = profileRepository.findById(stored.getId()).orElseThrow();
        assertThat(after.getSpecialtyCategoryId()).isEqualTo("cat-1");
        assertThat(after.getTeamIds()).containsExactly("team-1");
    }

    /**
     * A body that never mentions {@code teamIds} is not a body that cleared them.
     *
     * <p>This is the trap that rules out "copy all seven" on its own, quite apart from authority:
     * {@code Profile.teamIds} is initialised to an empty list, so a bound {@code Profile} is
     * <b>never null</b> there. A {@code teamIds != null} guard of the shape the other eleven use
     * would fire on every patch, and a clinician who changed their phone number would lose their
     * team membership. Reading the named fields from the JSON document rather than from the bound
     * entity is what makes absent and empty distinguishable.
     */
    @Test
    void anAbsentTeamIdsIsNotAChange() throws Exception {
        Profile stored = storedClinician();
        stored.setTeamIds(List.of("team-1", "team-2"));
        profileRepository.save(stored);

        patchWith(stored.getId(), "{\"id\":\"" + stored.getId() + "\",\"phoneNumber\":\"+233300000000\"}").andExpect(status().isOk());

        Profile after = profileRepository.findById(stored.getId()).orElseThrow();
        assertThat(after.getTeamIds()).as("a patch that never mentioned teams must not empty them").containsExactly("team-1", "team-2");
        assertThat(after.getPhoneNumber()).isEqualTo("+233300000000");
    }

    /**
     * Item 54's account takeover stays closed through this endpoint too.
     *
     * <p>{@code accountId} is {@code READ_ONLY}, so Jackson drops it before anything here sees it.
     * Asserted rather than assumed, because this change is the one that started reading the raw JSON
     * document, and a future author reaching for the raw node to "just apply what the caller named"
     * would reopen it. The request still sends {@code accountUid} — the field item 48 added and item
     * 50 deleted — so that an author who reinstates a second account identifier finds a test that
     * already names it.
     */
    @Test
    void accountIdRemainsUnwritable() throws Exception {
        Profile stored = storedClinician();

        patchWith(
            stored.getId(),
            "{\"id\":\"" + stored.getId() + "\",\"accountId\":\"somebody-else\",\"accountUid\":\"forged-uid\",\"firstName\":\"Adwoa\"}"
        ).andExpect(status().isOk());

        Profile after = profileRepository.findById(stored.getId()).orElseThrow();
        assertThat(after.getAccountId())
            .as("item 54 — a patch must not repoint a profile at another account")
            .isEqualTo("item60-clinician");
        assertThat(after.getFirstName()).as("the control: the request did land").isEqualTo("Adwoa");
    }

    // ---------------------------------------------------------------------------------------------
    // The rule that needs no list
    // ---------------------------------------------------------------------------------------------

    /**
     * Every field on {@link Profile} that a caller can send is either applied or refused — never
     * accepted and dropped.
     *
     * <p>This is item 60's "done when" stated as a property rather than as seven examples. It
     * reflects over the entity, patches each field on its own with a value that differs from what is
     * stored, and requires the answer to be a {@code 400} or a {@code 200} whose body shows the new
     * value. A field this test does not know how to build a value for fails loudly rather than being
     * skipped, because a silent skip here would reproduce the defect the class exists for.
     */
    @Test
    void everyProfileFieldIsEitherAppliedOrRefused() throws Exception {
        List<String> silentlyDropped = new ArrayList<>();
        // One profile for every field, on purpose: a refused patch leaves the row untouched and an
        // applied one only touches its own field, so no probe can mask the next. It is also the
        // difference between one round trip per field and three, which matters against the 15 s
        // per-test timeout in junit-platform.properties.
        Profile stored = storedClinician();

        for (Field field : Profile.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || NOT_A_PATCHABLE_FIELD.contains(field.getName())) {
                continue;
            }
            String probe = probeValueFor(field);
            String json = "{\"id\":\"" + stored.getId() + "\",\"" + field.getName() + "\":" + probe + "}";

            var response = patchWith(stored.getId(), json).andReturn().getResponse();
            if (response.getStatus() == 400) {
                continue;
            }
            assertThat(response.getStatus())
                .as("%s answered neither 400 nor 200 — %s", field.getName(), response.getContentAsString())
                .isEqualTo(200);
            if (!carries(om.readTree(response.getContentAsString()).get(field.getName()), om.readTree(probe))) {
                silentlyDropped.add(field.getName());
            }
        }

        assertThat(silentlyDropped)
            .as("these fields answered 200 and wrote nothing — decide: apply them, or refuse them in ProfileResource")
            .isEmpty();
    }

    /**
     * Whether the response carries what the probe asked for. An embedded object is compared entry by
     * entry, because the response serializes its unset members as nulls beside the one that was set;
     * an array is compared element by element on the same footing.
     *
     * <p><b>The array arm arrived with {@code contacts}</b> (profile.md T1) and it is not cosmetic.
     * Without it, {@code [{"name":"Probe Contact"}]} was compared to the response's
     * {@code [{"name":"Probe Contact","relationship":null,…}]} by whole-node equality, which is false
     * — so a correctly applied list would have been reported as silently dropped, and the honest fix
     * would have looked like a reason to exclude the field.
     */
    private boolean carries(JsonNode written, JsonNode expected) {
        if (written == null || written.isNull()) {
            return false;
        }
        if (expected.isArray()) {
            if (!written.isArray() || written.size() != expected.size()) {
                return false;
            }
            for (int i = 0; i < expected.size(); i++) {
                if (!carries(written.get(i), expected.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (!expected.isObject()) {
            return expected.equals(written);
        }
        Iterator<String> names = expected.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!expected.get(name).equals(written.get(name))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A JSON value for a probe patch, differing from what {@link #storedClinician} holds so that
     * "applied" is observable. Unknown types fail rather than being skipped.
     *
     * <p>⚠ <b>A collection is probed by its ELEMENT type, not by {@code List}</b> (profile.md T1), and
     * the alternative was a silent false pass rather than a failure. {@code Profile} now holds two
     * {@code List} fields whose elements are nothing alike — {@code teamIds} is
     * {@code List<String>} and {@code contacts} is {@code List<EmergencyContact>} — and
     * {@code field.getType()} answers {@code List} for both. Probing {@code contacts} with
     * {@code ["probe-id"]} makes Jackson fail to bind the document, the resource answers <b>400
     * Unreadable profile document</b>, and the loop above counts a 400 as "refused" and moves on. The
     * field would have read as decided while nothing about it had been decided at all — which is the
     * exact defect this class exists for, arriving through its own instrument.
     */
    private String probeValueFor(Field field) {
        Class<?> type = field.getType();
        if (type == String.class) {
            return "\"probe-" + field.getName() + "\"";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "true";
        }
        if (type == LocalDate.class) {
            return "\"1999-12-31\"";
        }
        if (type == Instant.class) {
            return "\"1999-12-31T00:00:00Z\"";
        }
        if (type == Address.class) {
            return "{\"streetAddress\":\"1 Probe Street\"}";
        }
        if (type == EmergencyContact.class) {
            return "{\"name\":\"Probe Contact\"}";
        }
        if (Collection.class.isAssignableFrom(type)) {
            return "[" + probeElementValueFor(field) + "]";
        }
        // Enums by reflection rather than by name, so the next one added needs no edit here. Any
        // constant differs from what storedClinician() holds, which is null for every enum field, so
        // "applied" stays observable without the probe having to know which value is special.
        if (type.isEnum()) {
            return "\"" + type.getEnumConstants()[0] + "\"";
        }
        return fail(
            String.format(
                "This test does not know how to build a probe value for Profile.%s of type %s. Teach it one rather " +
                "than excluding the field, or the field joins the seven this class exists for.",
                field.getName(),
                type.getName()
            )
        );
    }

    /**
     * One element of a collection field, resolved from its declared type argument.
     *
     * <p>A raw or wildcard collection fails rather than defaulting to a string: a default here is how
     * the {@code List}-shaped blind spot above would come back under a new name.
     */
    private String probeElementValueFor(Field field) {
        if (
            field.getGenericType() instanceof ParameterizedType parameterized &&
            parameterized.getActualTypeArguments().length == 1 &&
            parameterized.getActualTypeArguments()[0] instanceof Class<?> element
        ) {
            if (element == String.class) {
                return "\"probe-id\"";
            }
            if (element == EmergencyContact.class) {
                return "{\"name\":\"Probe Contact\"}";
            }
            return fail(
                String.format(
                    "This test does not know how to build a probe element for Profile.%s, a collection of %s. Teach " +
                    "it one rather than excluding the field: probing a collection with the wrong element type makes " +
                    "the body unreadable, which this class counts as a REFUSAL and would pass for nothing.",
                    field.getName(),
                    element.getName()
                )
            );
        }
        return fail(
            String.format(
                "Profile.%s is a collection with no resolvable element type (%s). A probe cannot be built for it, " +
                "and defaulting to a string is how the List-shaped blind spot this method exists to close would " +
                "return under another name.",
                field.getName(),
                field.getGenericType().getTypeName()
            )
        );
    }
}
