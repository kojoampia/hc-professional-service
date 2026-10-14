package net.jojoaddison.web.rest;

import static net.jojoaddison.security.WithMockGatewayUser.Factory.gatewayUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Address;
import net.jojoaddison.domain.EmergencyContact;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.domain.enumeration.Sex;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import net.jojoaddison.service.dto.AddressDTO;
import net.jojoaddison.service.dto.EmergencyContactDTO;
import net.jojoaddison.service.dto.ProfileDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What {@code PATCH /api/profiles/&#123;id&#125;} does with each field a caller can send, now that
 * the body binds to {@link ProfileDTO} rather than to the raw JSON document (the DTO rebinding,
 * 2026-10-14 — the architect's decision, and the tests moved to match it rather than the other way
 * round).
 *
 * <p><b>The contract this class pins.</b> The wire shape is {@link ProfileDTO} and nothing else:
 * a key the DTO does not declare — {@code specialtyCategoryId}, {@code teamIds}, the three push
 * preferences, the retired {@code emergencyContact} alias — is an unknown property and is silently
 * ignored. A key the DTO declares is either <em>applied</em> (through
 * {@code ProfileService.applyProvidedFields}) or <em>dropped</em>: {@code title} and
 * {@code middleNames} are declared on the DTO but never copied by {@code ProfileMapper.toEntity},
 * {@code status} is mapped onto the entity but never copied by {@code applyProvidedFields}, and
 * {@code accountId} likewise. There is no 400-refusal arm on this path any more —
 * {@code ProfileFieldOwnership} is no longer called here, so item 60's "applied or refused, never
 * dropped" invariant does not hold on this endpoint. {@link #everyWireFieldIsEitherAppliedOrDropped}
 * states the invariant that replaced it.
 *
 * <p><b>Four fields are mandatory in every body.</b> {@code ProfileMapper.toEntity} parses
 * {@code birthDate} ({@code LocalDate.parse}), {@code sex}, {@code cardType} and {@code status}
 * (three {@code valueOf} calls) unconditionally, so a body missing any of them — or carrying a value
 * outside the enumeration — answers 500 before the service sees the patch. Every body below carries
 * the four at the stored row's own values, so carrying them is not a change; the one helper,
 * {@link #patchBodyFor}, is where that lives.
 *
 * <p>Run as {@code ROLE_DOCTOR}, one of the six {@code CLINICAL_MUTATION} roles the blanket
 * {@code PATCH /api/**} rule admits, so every outcome below is this endpoint's own behaviour and not
 * the mutation matrix answering first.
 */
@AutoConfigureMockMvc
@IntegrationTest
@WithMockGatewayUser(authorities = { "ROLE_DOCTOR" })
class ProfilePatchFieldCoverageIT {

    private static final String ENTITY_API_URL_ID = "/api/profiles/{id}";

    /** The stored values {@link #storedClinician} writes and {@link #patchBodyFor} echoes. */
    private static final LocalDate STORED_BIRTH_DATE = LocalDate.of(1990, 1, 15);

    private static final Sex STORED_SEX = Sex.FEMALE;
    private static final DocumentType STORED_CARD_TYPE = DocumentType.GHANACARD;
    private static final ProfileStatus STORED_STATUS = ProfileStatus.ONBOARDING;

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
        Profile profile = new Profile().accountId("item60-clinician").firstName("Ama").lastName("Boateng");
        profile.setBirthDate(STORED_BIRTH_DATE);
        profile.setSex(STORED_SEX);
        profile.setCardType(STORED_CARD_TYPE);
        profile.setStatus(STORED_STATUS);
        return profileRepository.save(profile);
    }

    /**
     * The smallest body this endpoint accepts: the path key plus the four values
     * {@code ProfileMapper.toEntity} parses unconditionally, echoed at what {@link #storedClinician}
     * stores so that carrying them changes nothing. A test adds the field it is about on top.
     */
    private ObjectNode patchBodyFor(Profile stored) {
        ObjectNode body = om.createObjectNode();
        body.put("id", stored.getId());
        body.put("birthDate", STORED_BIRTH_DATE.toString());
        body.put("sex", STORED_SEX.name());
        body.put("cardType", STORED_CARD_TYPE.name());
        body.put("status", STORED_STATUS.name());
        return body;
    }

    private org.springframework.test.web.servlet.ResultActions patchWith(String id, String json) throws Exception {
        return restMockMvc.perform(
            patch(ENTITY_API_URL_ID, id).contentType("application/merge-patch+json").accept(MediaType.APPLICATION_JSON).content(json)
        );
    }

    // ---------------------------------------------------------------------------------------------
    // Applied fields
    // ---------------------------------------------------------------------------------------------

    /**
     * The positive control, without which every ignored-field test below would pass against a method
     * that applies nothing.
     */
    @Test
    void firstNameIsStillApplied() throws Exception {
        Profile stored = storedClinician();

        patchWith(stored.getId(), patchBodyFor(stored).put("firstName", "Adwoa").toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.firstName").value("Adwoa"));

        assertThat(profileRepository.findById(stored.getId()).orElseThrow().getFirstName()).isEqualTo("Adwoa");
    }

    /**
     * {@code contacts} is applied, and applied <b>whole</b> — replace rather than recursive merge,
     * the deviation from RFC 7396 that {@code ProfileService.applyProvidedFields} documents.
     *
     * <p>On the wire a contact's phone is {@code phoneNumber} ({@link EmergencyContactDTO}); the
     * entity stores it as {@code phone}. The mapper is the only translation between the two.
     */
    @Test
    void contactsAreAppliedRatherThanDropped() throws Exception {
        Profile stored = storedClinician();
        ObjectNode body = patchBodyFor(stored);
        ObjectNode contact = body.putArray("contacts").addObject();
        contact.put("name", "Efua Mensah").put("relationship", "sister").put("phoneNumber", "+233200000000");

        patchWith(stored.getId(), body.toString())
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
     * at least two emergency contacts.
     */
    @Test
    void severalContactsAreAllStored() throws Exception {
        Profile stored = storedClinician();
        ObjectNode body = patchBodyFor(stored);
        var contacts = body.putArray("contacts");
        contacts.addObject().put("name", "Efua Mensah").put("relationship", "sister").put("phoneNumber", "+233200000000");
        contacts.addObject().put("name", "Kojo Mensah").put("relationship", "brother").put("phoneNumber", "+233200000001");

        patchWith(stored.getId(), body.toString()).andExpect(status().isOk());

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
        ObjectNode body = patchBodyFor(stored);
        ObjectNode contact = body.putArray("contacts").addObject();
        contact.put("name", "Efua Mensah");
        contact.putObject("address").put("streetAddress", "12 Oxford St").put("city", "Accra").put("country", "Ghana");

        patchWith(stored.getId(), body.toString())
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

        ObjectNode body = patchBodyFor(stored);
        body.putArray("contacts").addObject().put("name", "Kojo Mensah");
        patchWith(stored.getId(), body.toString()).andExpect(status().isOk());

        List<EmergencyContact> saved = profileRepository.findById(stored.getId()).orElseThrow().getContacts();
        assertThat(saved).as("a replace, not an append — the old contact must not survive beside the new one").hasSize(1);
        assertThat(saved.get(0).getName()).isEqualTo("Kojo Mensah");
        assertThat(saved.get(0).getRelationship()).as("a replace, not a merge — the old relationship must not survive").isNull();
        assertThat(saved.get(0).getPhone()).as("a replace, not a merge — the old phone must not survive").isNull();
    }

    // ---------------------------------------------------------------------------------------------
    // The retired singular alias — an unknown key now
    // ---------------------------------------------------------------------------------------------

    /**
     * ⚠ <b>The retired {@code emergencyContact} alias no longer reaches storage: {@link ProfileDTO}
     * does not declare it, so it is an unknown key and is silently ignored.</b>
     *
     * <p>That consequence is live and client-facing, which is why this test exists rather than being
     * deleted with the alias: {@code web/}'s wire type and {@code mobile/}'s {@code me.page.ts} both
     * still send {@code emergencyContact} (dropping it is T6's work), and against this endpoint that
     * write now answers 200 with the next of kin quietly not saved. This test pins the server half of
     * that fact; the client half is T6's to retire.
     */
    @Test
    void theRetiredSingularNameIsIgnoredOnTheWire() throws Exception {
        Profile stored = storedClinician();
        ObjectNode body = patchBodyFor(stored);
        body.putObject("emergencyContact").put("name", "Efua Mensah").put("relationship", "sister");

        patchWith(stored.getId(), body.toString()).andExpect(status().isOk());

        List<EmergencyContact> saved = profileRepository.findById(stored.getId()).orElseThrow().getContacts();
        assertThat(saved).as("the alias is an unknown key on the DTO wire — nothing may land in the list").isNullOrEmpty();
    }

    /**
     * And when a body carries both names, {@code contacts} wins in either order — the alias is an
     * unknown key, so only {@code contacts} ever binds, whichever side of it the alias sits on.
     */
    @Test
    void whenABodyCarriesBothNamesTheListWins() throws Exception {
        Profile first = storedClinician();
        ObjectNode firstBody = patchBodyFor(first);
        firstBody.putArray("contacts").addObject().put("name", "From contacts");
        firstBody.putObject("emergencyContact").put("name", "From alias");
        patchWith(first.getId(), firstBody.toString()).andExpect(status().isOk());
        assertThat(profileRepository.findById(first.getId()).orElseThrow().getContacts())
            .extracting(EmergencyContact::getName)
            .containsExactly("From contacts");
        profileRepository.deleteAll();

        Profile second = storedClinician();
        ObjectNode secondBody = patchBodyFor(second);
        secondBody.putObject("emergencyContact").put("name", "From alias");
        secondBody.putArray("contacts").addObject().put("name", "From contacts");
        patchWith(second.getId(), secondBody.toString()).andExpect(status().isOk());
        assertThat(profileRepository.findById(second.getId()).orElseThrow().getContacts())
            .extracting(EmergencyContact::getName)
            .containsExactly("From contacts");
    }

    // ---------------------------------------------------------------------------------------------
    // Ignored fields, one test each — 200 and the stored value untouched
    // ---------------------------------------------------------------------------------------------

    /**
     * Asserts that a field this wire does not carry is ignored: 200, the stored value unchanged, and
     * the rest of the body still applied — the positive control rides in the same request so that a
     * handler refusing everything cannot pass.
     *
     * <p>These five answered <b>400 naming the owning endpoint</b> before the DTO rebinding
     * ({@code ProfileFieldOwnership}, item 60). That refusal arm is gone from this path: the body
     * binds to {@link ProfileDTO}, which does not declare them, so they are unknown keys and Jackson
     * ignores them before the handler runs. The owning endpoints themselves are unchanged —
     * {@code PUT /api/professional-application/&#123;id&#125;/organization} for the organisation
     * pair, {@code PUT /api/notifications/preferences} for the push preferences.
     */
    private void assertIgnored(String field, ObjectNode bodyWithField, Function<Profile, Object> read, Object storedValue, Profile stored)
        throws Exception {
        bodyWithField.put("firstName", "Adwoa");
        patchWith(stored.getId(), bodyWithField.toString()).andExpect(status().isOk());

        Profile after = profileRepository.findById(stored.getId()).orElseThrow();
        assertThat(read.apply(after))
            .as("%s is not on the ProfileDTO wire — the stored value must be untouched", field)
            .isEqualTo(storedValue);
        assertThat(after.getFirstName()).as("the control: the rest of the request did land").isEqualTo("Adwoa");
    }

    @Test
    void specialtyCategoryIdIsIgnoredOnTheWire() throws Exception {
        Profile stored = storedClinician();
        stored.setSpecialtyCategoryId("stored-category");
        profileRepository.save(stored);

        assertIgnored(
            "specialtyCategoryId",
            patchBodyFor(stored).put("specialtyCategoryId", "some-category"),
            Profile::getSpecialtyCategoryId,
            "stored-category",
            stored
        );
    }

    @Test
    void teamIdsAreIgnoredOnTheWire() throws Exception {
        Profile stored = storedClinician();
        stored.setTeamIds(List.of("stored-team"));
        profileRepository.save(stored);

        ObjectNode body = patchBodyFor(stored);
        body.putArray("teamIds").add("some-team");
        assertIgnored("teamIds", body, Profile::getTeamIds, List.of("stored-team"), stored);
    }

    @Test
    void pushMessagesEnabledIsIgnoredOnTheWire() throws Exception {
        Profile stored = storedClinician();
        stored.setPushMessagesEnabled(Boolean.TRUE);
        profileRepository.save(stored);

        assertIgnored(
            "pushMessagesEnabled",
            patchBodyFor(stored).put("pushMessagesEnabled", false),
            Profile::getPushMessagesEnabled,
            Boolean.TRUE,
            stored
        );
    }

    @Test
    void pushComplianceEnabledIsIgnoredOnTheWire() throws Exception {
        Profile stored = storedClinician();
        stored.setPushComplianceEnabled(Boolean.TRUE);
        profileRepository.save(stored);

        assertIgnored(
            "pushComplianceEnabled",
            patchBodyFor(stored).put("pushComplianceEnabled", false),
            Profile::getPushComplianceEnabled,
            Boolean.TRUE,
            stored
        );
    }

    @Test
    void pushShowSenderNameIsIgnoredOnTheWire() throws Exception {
        Profile stored = storedClinician();
        stored.setPushShowSenderName(Boolean.FALSE);
        profileRepository.save(stored);

        assertIgnored(
            "pushShowSenderName",
            patchBodyFor(stored).put("pushShowSenderName", true),
            Profile::getPushShowSenderName,
            Boolean.FALSE,
            stored
        );
    }

    // ---------------------------------------------------------------------------------------------
    // Declared on the DTO and dropped anyway
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code title} is declared on {@link ProfileDTO} and never copied by
     * {@code ProfileMapper.toEntity}, so it is accepted on the wire and dropped before the service
     * sees it — there is currently no HTTP path that writes a title at all.
     */
    @Test
    void titleIsDroppedByTheWire() throws Exception {
        Profile stored = storedClinician();

        patchWith(stored.getId(), patchBodyFor(stored).put("title", "Prof").toString()).andExpect(status().isOk());

        assertThat(profileRepository.findById(stored.getId()).orElseThrow().getTitle())
            .as("the mapper does not copy title — nothing may be stored")
            .isNull();
    }

    /**
     * {@code status} is the state machine's own field, and a merge-patch still cannot move it — but
     * the mechanism changed. The body <b>must</b> carry a status (the mapper parses it
     * unconditionally), the mapper writes it onto the entity, and
     * {@code ProfileService.applyProvidedFields} then never copies it onto the stored row. So the
     * answer is 200 with the stored status untouched, where it used to be a 400 naming the
     * transition endpoints.
     */
    @Test
    void statusIsDroppedAtTheService() throws Exception {
        Profile stored = storedClinician();

        patchWith(stored.getId(), patchBodyFor(stored).put("status", ProfileStatus.ACTIVE.name()).toString()).andExpect(status().isOk());

        assertThat(profileRepository.findById(stored.getId()).orElseThrow().getStatus())
            .as("applyProvidedFields never copies status — the stored value must be untouched")
            .isEqualTo(STORED_STATUS);
    }

    // ---------------------------------------------------------------------------------------------
    // Controls
    // ---------------------------------------------------------------------------------------------

    /**
     * A read-modify-write client is not punished for carrying the protected fields back unchanged.
     *
     * <p>The read-back document uses the <em>entity's</em> field names ({@code mobilePhone}, a
     * contact's {@code phone}, {@code specialtyCategoryId}, {@code teamIds}, the push flags), most of
     * which the DTO wire does not declare — so on the way back in they are ignored rather than
     * applied, and the one field the client edited still lands. The stored row carries the four
     * mandatory values, so the round-tripped document parses.
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
        String roundTrip = ((ObjectNode) readBack).put("firstName", "Adwoa").toString();

        patchWith(stored.getId(), roundTrip).andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value("Adwoa"));

        Profile after = profileRepository.findById(stored.getId()).orElseThrow();
        assertThat(after.getSpecialtyCategoryId()).isEqualTo("cat-1");
        assertThat(after.getTeamIds()).containsExactly("team-1");
    }

    /**
     * A body that never mentions {@code teamIds} is not a body that cleared them.
     *
     * <p>{@code teamIds} cannot be named on this wire at all now — {@link ProfileDTO} does not
     * declare it — which makes this the easy direction. It is still asserted, because the defect it
     * guards against is one DTO field away: {@code Profile.teamIds} is initialised to an empty list,
     * so a future {@code ProfileDTO.teamIds} mapped without a null guard would empty a clinician's
     * teams on every patch that changed a phone number.
     */
    @Test
    void anAbsentTeamIdsIsNotAChange() throws Exception {
        Profile stored = storedClinician();
        stored.setTeamIds(List.of("team-1", "team-2"));
        profileRepository.save(stored);

        patchWith(stored.getId(), patchBodyFor(stored).put("phoneNumber", "+233300000000").toString()).andExpect(status().isOk());

        Profile after = profileRepository.findById(stored.getId()).orElseThrow();
        assertThat(after.getTeamIds()).as("a patch that never mentioned teams must not empty them").containsExactly("team-1", "team-2");
        assertThat(after.getPhoneNumber()).isEqualTo("+233300000000");
    }

    /**
     * Item 54's account takeover stays closed through this endpoint too — but the guard moved.
     *
     * <p>{@code Profile.accountId} is {@code READ_ONLY} to Jackson, which this wire no longer passes
     * through: {@link ProfileDTO} declares {@code accountId} as an ordinary writable field and
     * {@code ProfileMapper.toEntity} copies it onto the entity. What keeps the takeover closed is
     * {@code ProfileService.applyProvidedFields} alone, which never copies {@code accountId} onto the
     * stored row. That is one guard where there used to be two, so this test is now the only thing
     * standing between a future "just apply what the caller named" edit and item 54 reopening.
     */
    @Test
    void accountIdRemainsUnwritable() throws Exception {
        Profile stored = storedClinician();

        ObjectNode body = patchBodyFor(stored).put("accountId", "somebody-else").put("firstName", "Adwoa");
        body.put("accountUid", "forged-uid");
        patchWith(stored.getId(), body.toString()).andExpect(status().isOk());

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
     * The DTO fields this wire accepts and then drops, each for a located reason: {@code title} and
     * {@code middleNames} are never copied by {@code ProfileMapper.toEntity}; {@code status} and
     * {@code accountId} are mapped onto the entity and never copied by
     * {@code applyProvidedFields}.
     *
     * <p>The walk below holds this list exact in both directions — a dropped field missing from it
     * fails, and an entry that turns out to be applied fails too, so the list cannot go stale
     * silently.
     */
    private static final Set<String> DROPPED_ON_THE_WIRE = Set.of("title", "middleNames", "status", "accountId");

    /**
     * Every field {@link ProfileDTO} declares is either applied or in {@link #DROPPED_ON_THE_WIRE} —
     * never a third thing.
     *
     * <p>This is the successor to item 60's "applied or refused" walk over the entity. The entity is
     * no longer the wire: a field added to {@code Profile} alone is unreachable from here by
     * construction, so reflecting over it would probe keys Jackson ignores and learn nothing. The DTO
     * is where a new field becomes reachable, so the DTO is what the walk enumerates — a field added
     * to {@link ProfileDTO} fails here until somebody decides which side it falls on, which is the
     * property the old test existed for, restated against the new boundary.
     */
    @Test
    void everyWireFieldIsEitherAppliedOrDropped() throws Exception {
        List<String> undecided = new ArrayList<>();
        // One profile for every field, on purpose: a dropped field leaves the row untouched and an
        // applied one only touches its own field, so no probe can mask the next. It is also the
        // difference between one round trip per field and sixteen, which matters against the 15 s
        // per-test timeout in junit-platform.properties.
        Profile stored = storedClinician();

        for (Field field : ProfileDTO.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || "id".equals(field.getName())) {
                continue;
            }
            JsonNode probe = om.readTree(probeValueFor(field));
            ObjectNode body = patchBodyFor(stored);
            body.set(field.getName(), probe);

            var response = patchWith(stored.getId(), body.toString()).andReturn().getResponse();
            assertThat(response.getStatus())
                .as("%s answered %s — %s", field.getName(), response.getStatus(), response.getContentAsString())
                .isEqualTo(200);

            JsonNode written = om.readTree(response.getContentAsString()).get(entityNameFor(field.getName()));
            boolean applied = carries(written, probe);
            if (applied && DROPPED_ON_THE_WIRE.contains(field.getName())) {
                undecided.add(field.getName() + " is listed as dropped but was applied — the list is stale");
            }
            if (!applied && !DROPPED_ON_THE_WIRE.contains(field.getName())) {
                undecided.add(field.getName() + " answered 200 and wrote nothing, and is not in DROPPED_ON_THE_WIRE");
            }
        }

        assertThat(undecided).as("every ProfileDTO field is applied or declared dropped — these are neither").isEmpty();
    }

    /**
     * The entity field a DTO field lands in, where the two names differ. The response body is the
     * entity, so the walk reads it under the entity's name.
     */
    private String entityNameFor(String dtoField) {
        return "mobileNumber".equals(dtoField) ? "mobilePhone" : dtoField;
    }

    /**
     * Whether the response carries what the probe asked for. An embedded object is compared entry by
     * entry, because the response serializes its unset members as nulls beside the one that was set;
     * an array is compared element by element on the same footing.
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
     * <p>Four fields are probed by name before any type rule: the mapper parses them, so the probe
     * must be a <em>valid</em> value that differs from the stored one — an arbitrary string would
     * turn the whole walk into a 500.
     */
    private String probeValueFor(Field field) {
        switch (field.getName()) {
            case "birthDate":
                return "\"1999-12-31\"";
            case "sex":
                return "\"" + Sex.MALE.name() + "\"";
            case "cardType":
                return "\"" + DocumentType.PASSPORT.name() + "\"";
            case "status":
                return "\"" + ProfileStatus.ACTIVE.name() + "\"";
            default:
            // fall through to the type rules
        }
        Class<?> type = field.getType();
        if (type == String.class) {
            return "\"probe-" + field.getName() + "\"";
        }
        if (type == AddressDTO.class) {
            return "{\"streetAddress\":\"1 Probe Street\"}";
        }
        if (java.util.Collection.class.isAssignableFrom(type)) {
            return "[" + probeElementValueFor(field) + "]";
        }
        return fail(
            String.format(
                "This test does not know how to build a probe value for ProfileDTO.%s of type %s. Teach it one rather " +
                "than excluding the field, or the field becomes the undecided third thing this class exists to refuse.",
                field.getName(),
                type.getName()
            )
        );
    }

    /**
     * One element of a collection field, resolved from its declared type argument. A raw or wildcard
     * collection fails rather than defaulting to a string.
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
            if (element == EmergencyContactDTO.class) {
                return "{\"name\":\"Probe Contact\"}";
            }
            return fail(
                String.format(
                    "This test does not know how to build a probe element for ProfileDTO.%s, a collection of %s. Teach " +
                    "it one rather than excluding the field.",
                    field.getName(),
                    element.getName()
                )
            );
        }
        return fail(
            String.format(
                "ProfileDTO.%s is a collection with no resolvable element type (%s). A probe cannot be built for it.",
                field.getName(),
                field.getGenericType().getTypeName()
            )
        );
    }
}
