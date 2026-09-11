package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * <p><b>Why the split is 2 / 5 rather than all-copy or all-refuse.</b> The seven are not alike, and
 * the argument is in {@link ProfileResource#PATCH_REFUSED_FIELDS}. In short: {@code title} and
 * {@code emergencyContact} are the clinician's own data, written by
 * {@code OnboardingService.upsertOwnProfile} on exactly the same footing as the eleven already
 * copied; the other five have an owning endpoint with narrower authority than this one, and copying
 * them here would create a second writer that skips it.
 *
 * <p><b>The last test is the one that will still be right next year.</b> The seven named tests pin
 * today's seven; {@link #everyProfileFieldIsEitherAppliedOrRefused} reflects over {@code Profile}
 * and requires an answer for <em>every</em> field, so a field added later — or restored by a
 * regeneration, since {@code .jhipster/Profile.json} lists all seven and would re-emit them into
 * {@code partialUpdate} — fails here until somebody decides which side it falls on. (It listed
 * <em>four</em> of the seven until backlog.md item 21 added the three push preferences that file had
 * been missing since MOB9, so the regeneration hazard named here is now larger than when it was
 * written, not smaller.) That is the
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
     * {@code emergencyContact} is applied, and applied <b>whole</b>.
     *
     * <p>Replace rather than recursive merge, which RFC 7396 — the media type this endpoint consumes
     * — asks for on a nested object. The deviation is deliberate and is <em>not</em> justified by
     * impossibility: since this change {@code ProfileResource} holds the raw node, so a merge could
     * be reconstructed there. It is not, because {@code address} — the other embedded object
     * {@code partialUpdate} copies — has always replaced wholesale, and so does
     * {@code upsertOwnProfile} with this very field; a merge here would make the two write paths
     * disagree about what writing an emergency contact means.
     */
    @Test
    void emergencyContactIsAppliedRatherThanDropped() throws Exception {
        Profile stored = storedClinician();

        patchWith(
            stored.getId(),
            "{\"id\":\"" +
            stored.getId() +
            "\",\"emergencyContact\":{\"name\":\"Efua Mensah\",\"relationship\":\"sister\"," +
            "\"phone\":\"+233200000000\"}}"
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emergencyContact.name").value("Efua Mensah"));

        EmergencyContact saved = profileRepository.findById(stored.getId()).orElseThrow().getEmergencyContact();
        assertThat(saved).isNotNull();
        assertThat(saved.getName()).isEqualTo("Efua Mensah");
        assertThat(saved.getRelationship()).isEqualTo("sister");
        assertThat(saved.getPhone()).isEqualTo("+233200000000");
    }

    /**
     * A second {@code emergencyContact} write replaces the stored one rather than merging into it.
     *
     * <p>Stated separately because "applied" and "applied whole" are different claims and only the
     * second one rules out a future recursive merge being added without a decision.
     */
    @Test
    void aSecondEmergencyContactReplacesTheFirstWholesale() throws Exception {
        Profile stored = storedClinician();
        stored.setEmergencyContact(new EmergencyContact().name("Efua Mensah").relationship("sister").phone("+233200000000"));
        profileRepository.save(stored);

        patchWith(stored.getId(), "{\"id\":\"" + stored.getId() + "\",\"emergencyContact\":{\"name\":\"Kojo Mensah\"}}").andExpect(
            status().isOk()
        );

        EmergencyContact saved = profileRepository.findById(stored.getId()).orElseThrow().getEmergencyContact();
        assertThat(saved.getName()).isEqualTo("Kojo Mensah");
        assertThat(saved.getRelationship()).as("a replace, not a merge — the old relationship must not survive").isNull();
        assertThat(saved.getPhone()).as("a replace, not a merge — the old phone must not survive").isNull();
    }

    // ---------------------------------------------------------------------------------------------
    // The five that are refused, one test each
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
    private static final String ORGANISATION_ENDPOINT = "PUT /api/onboarding/applications/{id}/organization";

    /** The endpoint that owns the push preferences, per {@code PATCH_REFUSED_FIELDS}. */
    private static final String PREFERENCES_ENDPOINT = "PUT /api/notifications/preferences";

    /**
     * The specialty is assigned by {@code PUT /api/onboarding/applications/&#123;id&#125;/organization},
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

        JsonNode readBack = om.readTree(
            restMockMvc
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(ENTITY_API_URL_ID, stored.getId()))
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
     * entry, because the response serializes its unset members as nulls beside the one that was set.
     */
    private boolean carries(JsonNode written, JsonNode expected) {
        if (written == null || written.isNull()) {
            return false;
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
        if (type == List.class) {
            return "[\"probe-id\"]";
        }
        if (type == Address.class) {
            return "{\"streetAddress\":\"1 Probe Street\"}";
        }
        if (type == EmergencyContact.class) {
            return "{\"name\":\"Probe Contact\"}";
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
}
