package net.jojoaddison.web.rest;

import static net.jojoaddison.security.WithMockGatewayUser.Factory.accountIdFor;
import static net.jojoaddison.security.WithMockGatewayUser.Factory.gatewayUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Address;
import net.jojoaddison.domain.EmergencyContact;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.domain.enumeration.Sex;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code GET} and {@code PUT /api/profile} — the caller's own profile (profile.md step 2).
 *
 * <h2>Named for the PATH and not for a class, deliberately (F3)</h2>
 *
 * <p>This was {@code OwnProfileResourceIT} and there is no {@code OwnProfileResource} any more:
 * {@code profile.md} names {@code ProfileResource.java} as the file that serves {@code api/profile},
 * so the two handlers moved there and are gated per handler. The path is the stable subject — it is
 * what the specification names and what every client calls — so the test is named after it rather
 * than after whichever class currently holds the mapping.
 *
 * <h2>The wire is {@code ProfileDTO}, not the entity</h2>
 *
 * <p>The write binds {@code ProfileDTO} and reaches the entity through {@code ProfileMapper.toEntity},
 * which parses four fields unconditionally — {@code LocalDate.parse(birthDate)},
 * {@code Sex.valueOf(sex)}, {@code DocumentType.valueOf(cardType)},
 * {@code ProfileStatus.valueOf(status)} — so <b>every body must carry all four</b> or the write
 * answers 500 before any service rule runs. {@code status} is then dropped by
 * {@code ProfileService.applyProvidedFields}, so the value sent never reaches the row. The DTO's
 * field names differ from the entity's where they differ on the wire: {@code mobileNumber} (entity
 * {@code mobilePhone}) and a contact's {@code phoneNumber} (entity {@code phone}). {@code title} and
 * {@code middleNames} are bound and never mapped, so the wire cannot deliver either. A key the DTO
 * does not declare is ignored.
 *
 * <h2>What this class is for, as distinct from its neighbours</h2>
 *
 * <p>{@code ClinicalAuthorityMatrixIT} holds <em>who may call it</em>. {@code ProfilePatchFieldCoverageIT}
 * holds <em>which fields any partial write applies or drops</em>. This class holds the properties
 * that are specific to the endpoint being <b>own-scoped</b> and <b>partial</b>: that a pane saving
 * its own slice does not blank the panes before it, that the body cannot select whose row is
 * written, and what becomes of the fields the write path does not apply.
 *
 * <p>Run as {@code ROLE_USER} throughout — an applicant, which is who step 2 is written by, and who
 * every other write path in this service refuses.
 */
@AutoConfigureMockMvc
@IntegrationTest
@WithMockGatewayUser(login = "own-profile-applicant", authorities = { "ROLE_USER" })
class OwnProfilePathIT {

    private static final String OWN_PROFILE_URL = "/api/profile";

    /** The {@code uid} claim {@link WithMockGatewayUser} derives for this class's caller. */
    private static final String CALLER_ACCOUNT = accountIdFor("own-profile-applicant");

    /**
     * The four fields {@code ProfileMapper.toEntity} parses unconditionally. Absent, the write is a
     * 500 (an NPE inside the mapper), so every body in this class carries them. {@code status} is
     * parsed and then dropped by {@code applyProvidedFields} — {@code CREATED} here never reaches
     * the row.
     */
    private static final String MANDATORY_WIRE_FIELDS =
        "\"birthDate\":\"1990-01-01\",\"sex\":\"FEMALE\",\"cardType\":\"GHANACARD\",\"status\":\"CREATED\"";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @AfterEach
    void cleanup() {
        profileRepository.deleteAll();
    }

    private ResultActions putOwnProfile(String json) throws Exception {
        return restMockMvc.perform(
            put(OWN_PROFILE_URL).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(json)
        );
    }

    /** A body carrying {@link #MANDATORY_WIRE_FIELDS} plus the fragment under test. */
    private static String dtoBody(String extras) {
        return "{" + MANDATORY_WIRE_FIELDS + (extras.isEmpty() ? "" : "," + extras) + "}";
    }

    private Profile storedCallerProfile() {
        return profileRepository.save(new Profile().accountId(CALLER_ACCOUNT).firstName("Ama").lastName("Boateng"));
    }

    private Profile reloadCaller() {
        return profileRepository.findByAccountId(CALLER_ACCOUNT).orElseThrow();
    }

    // ---------------------------------------------------------------------------------------------
    // The read
    // ---------------------------------------------------------------------------------------------

    /** The caller's own row, resolved from the token and not from anything in the request. */
    @Test
    void theCallerReadsTheirOwnProfile() throws Exception {
        storedCallerProfile();

        restMockMvc
            .perform(get(OWN_PROFILE_URL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.firstName").value("Ama"))
            .andExpect(jsonPath("$.accountId").value(CALLER_ACCOUNT));
    }

    /**
     * 404 before step 2 has been completed, which is a state and not an error.
     *
     * <p>Follows {@code getOwnApplication}, which throws {@code NOT_FOUND} for exactly this (as did
     * the retired {@code GET /api/onboarding/profile}); {@code web/}'s {@code error-handler.interceptor.ts}
     * already opts the own-application read out of the global error banner <em>because</em> an
     * untreated 404 was visible in the deployed portal, so the client-side precedent exists too.
     *
     * <p>An empty 200 was refused: it makes "no profile yet" and "a profile with nothing in it" the
     * same answer, and step 3 keys off {@code Profile.id}.
     */
    @Test
    void aCallerWithNoProfileYetGetsA404RatherThanAnEmptyOne() throws Exception {
        restMockMvc.perform(get(OWN_PROFILE_URL)).andExpect(status().isNotFound());
    }

    /**
     * ⚠ <b>The read must not answer with somebody else's row just because theirs is the only one.</b>
     *
     * <p>A lookup that fell back to "the first profile" or that resolved through a login rather than
     * the {@code uid} claim would pass every other case in this class while handing a colleague's
     * 23-field document to an applicant. {@link WithMockGatewayUser} makes the account id
     * deliberately differ from the login for this reason, so an implementation that fell back to the
     * JWT subject finds nothing rather than finding something plausible.
     */
    @Test
    void theReadDoesNotFallBackToSomebodyElsesProfile() throws Exception {
        profileRepository.save(new Profile().accountId("some-other-account").firstName("Kwame").cardNumber("GHA-SECRET"));

        restMockMvc.perform(get(OWN_PROFILE_URL)).andExpect(status().isNotFound());
    }

    /**
     * {@code status} is readable here and dropped on the write, which is one field answering two
     * different questions.
     *
     * <p>{@code profile.md} renders {@code profile.status} in the page header, so the read has to
     * carry it; the write parses it ({@code ProfileMapper} requires it on every body) and
     * {@code applyProvidedFields} never copies it, because the state machine owns it. See
     * {@link #statusInTheBodyIsDroppedRatherThanApplied}.
     */
    @Test
    void theReadCarriesStatusEvenThoughTheWriteDropsIt() throws Exception {
        Profile stored = storedCallerProfile();
        stored.setStatus(ProfileStatus.CREDENTIAL_REVIEW);
        profileRepository.save(stored);

        restMockMvc.perform(get(OWN_PROFILE_URL)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CREDENTIAL_REVIEW"));
    }

    // ---------------------------------------------------------------------------------------------
    // The write: partial, which is the defect this endpoint exists to avoid
    // ---------------------------------------------------------------------------------------------

    /**
     * ⛔ <b>The case this endpoint exists for: a write that omits a field must not blank it.</b>
     *
     * <p>{@code profile.md} specifies a dialog panel per step, so a pane saves only its own slice. The
     * endpoint it replaced — {@code PUT /api/onboarding/profile} through
     * {@code OnboardingService.upsertOwnProfile}, both retired by F8 — wrote thirteen fields with no
     * {@code != null} guards, so the next-of-kin pane would blank the address the address pane had
     * just saved, and answer 200 with a body confirming the loss. Nothing about the status code can
     * see that, which is why this asserts the stored row.
     *
     * <p>Two writes, as a real wizard would make them, rather than one write and an inspection: the
     * claim is about what the <em>second</em> request does to the <em>first</em> request's fields.
     * Every pane must now also carry the four wire-mandatory fields, so the claim is narrowed to the
     * fields a pane can genuinely omit — here {@code firstName}, {@code lastName} and {@code address}
     * surviving the contacts pane.
     */
    @Test
    void aLaterWriteDoesNotBlankTheFieldsAnEarlierWriteSet() throws Exception {
        putOwnProfile(
            dtoBody(
                "\"firstName\":\"Ama\",\"lastName\":\"Boateng\"," +
                "\"address\":{\"streetAddress\":\"12 Oxford St\",\"city\":\"Accra\",\"country\":\"Ghana\"}"
            )
        ).andExpect(status().isOk());

        // The next-of-kin pane. It knows nothing about the address pane and sends none of its fields.
        putOwnProfile(
            dtoBody("\"contacts\":[{\"name\":\"Efua Mensah\",\"relationship\":\"sister\",\"phoneNumber\":\"+233200000000\"}]")
        ).andExpect(status().isOk());

        Profile after = reloadCaller();
        assertThat(after.getContacts()).as("the second pane's own slice landed").hasSize(1);
        assertThat(after.getFirstName()).as("a pane that did not mention firstName must not have cleared it").isEqualTo("Ama");
        assertThat(after.getLastName()).isEqualTo("Boateng");
        assertThat(after.getBirthDate()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(after.getAddress()).as("the address pane's work must survive the next-of-kin pane").isNotNull();
        assertThat(after.getAddress().getStreetAddress()).isEqualTo("12 Oxford St");
    }

    /**
     * Step 2 <em>is</em> "create the profile", so the first write creates the row and owns it.
     *
     * <p>The account id is the caller's, from the token. A create that stored null there would be
     * backlog.md item 66 again — a profile belonging to nobody, returned with a 200, which no later
     * call can repair: the DTO's {@code accountId} is bound and no write path copies it.
     */
    @Test
    void theFirstWriteCreatesTheProfileOwnedByTheCaller() throws Exception {
        assertThat(profileRepository.findByAccountId(CALLER_ACCOUNT)).isEmpty();

        putOwnProfile(dtoBody("\"firstName\":\"Ama\"")).andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(CALLER_ACCOUNT));

        Profile created = reloadCaller();
        assertThat(created.getId()).as("step 3 keys off Profile.id, so the create has to produce one").isNotNull();
        assertThat(created.getFirstName()).isEqualTo("Ama");
    }

    /** A second write adopts the row rather than making a second one. */
    @Test
    void aSecondWriteAdoptsTheExistingRowRatherThanCreatingAnother() throws Exception {
        putOwnProfile(dtoBody("\"firstName\":\"Ama\"")).andExpect(status().isOk());
        String id = reloadCaller().getId();

        putOwnProfile(dtoBody("\"lastName\":\"Boateng\"")).andExpect(status().isOk());

        assertThat(profileRepository.findAll()).as("one account, one profile").hasSize(1);
        assertThat(reloadCaller().getId()).isEqualTo(id);
    }

    /**
     * {@code middleNames} is accepted on the wire and dropped: {@code ProfileDTO} declares it and
     * {@code ProfileMapper.toEntity} never copies it, so no applicant-facing write path stores it —
     * the field the wizard collects (profile.md's Profile model lists {@code middleNames}) is
     * unreachable from the wire, and {@code ProfileCompleteness} requires it, so completeness is only
     * reachable for a row that already holds one. The stamp cases below pre-seed it for exactly that
     * reason.
     */
    @Test
    void middleNamesIsDroppedByTheWire() throws Exception {
        putOwnProfile(dtoBody("\"firstName\":\"Ama\",\"middleNames\":\"Nana Yaa\",\"lastName\":\"Boateng\"")).andExpect(status().isOk());

        assertThat(reloadCaller().getMiddleNames()).as("ProfileMapper.toEntity never copies middleNames").isNull();
        assertThat(reloadCaller().getFirstName()).as("the control: the rest of the body did land").isEqualTo("Ama");
    }

    /** At least two contacts, each with a structured address — what {@code profile.md} step 2 collects. */
    @Test
    void twoContactsWithStructuredAddressesRoundTrip() throws Exception {
        putOwnProfile(
            dtoBody(
                "\"contacts\":[" +
                "{\"name\":\"Efua Mensah\",\"relationship\":\"sister\",\"phoneNumber\":\"+233200000000\"," +
                "\"address\":{\"streetAddress\":\"12 Oxford St\",\"city\":\"Accra\"}}," +
                "{\"name\":\"Kojo Mensah\",\"relationship\":\"brother\",\"phoneNumber\":\"+233200000001\"," +
                "\"address\":{\"streetAddress\":\"3 High St\",\"city\":\"Kumasi\"}}]"
            )
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts[1].address.city").value("Kumasi"));

        List<EmergencyContact> contacts = reloadCaller().getContacts();
        assertThat(contacts).hasSize(2);
        assertThat(contacts.get(0).getPhone()).as("the wire's phoneNumber lands on the entity's phone").isEqualTo("+233200000000");
        assertThat(contacts.get(0).getAddress()).isNotNull();
        assertThat(contacts.get(0).getAddress().getStreetAddress()).isEqualTo("12 Oxford St");
        assertThat(contacts.get(1).getAddress().getCity()).isEqualTo("Kumasi");
    }

    // ---------------------------------------------------------------------------------------------
    // Ownership: the body cannot choose whose row is written
    // ---------------------------------------------------------------------------------------------

    /**
     * ⛔ <b>A write from account A naming account B must not reach B's row.</b>
     *
     * <p>This is backlog.md item 54's account takeover asked of the DTO-bound endpoint. The DTO
     * carries {@code accountId} and {@code ProfileMapper.toEntity} copies it onto the entity, so the
     * whole weight is on {@code ProfileService.partialUpdateOwnProfile}: the row is looked up by the
     * caller's token-derived account id and {@code applyProvidedFields} never copies {@code accountId}
     * or {@code id}. The body also carries {@code id} — the other identifier a caller might hope
     * selects a row — and {@code accountUid}, the field item 48 added and item 50 deleted (now an
     * unknown key, ignored), so an author who reinstates a second account identifier finds a test
     * that already names it.
     */
    @Test
    void aWriteNamingAnotherAccountCannotReachThatAccountsProfile() throws Exception {
        Profile victim = profileRepository.save(
            new Profile().accountId("victim-account").firstName("Kwame").cardNumber("GHA-VICTIM").lastName("Asante")
        );

        putOwnProfile(
            dtoBody(
                "\"id\":\"" +
                victim.getId() +
                "\",\"accountId\":\"victim-account\",\"accountUid\":\"victim-account\"," +
                "\"firstName\":\"Attacker\",\"cardNumber\":\"GHA-FORGED\""
            )
        ).andExpect(status().isOk());

        Profile victimAfter = profileRepository.findById(victim.getId()).orElseThrow();
        assertThat(victimAfter.getFirstName()).as("item 54 — a write must not reach another account's row").isEqualTo("Kwame");
        assertThat(victimAfter.getCardNumber()).isEqualTo("GHA-VICTIM");
        assertThat(victimAfter.getAccountId()).isEqualTo("victim-account");

        Profile caller = reloadCaller();
        assertThat(caller.getId()).as("the write landed on the caller's own new row instead").isNotEqualTo(victim.getId());
        assertThat(caller.getAccountId()).isEqualTo(CALLER_ACCOUNT);
        assertThat(caller.getFirstName()).as("the control: the request did land somewhere").isEqualTo("Attacker");
    }

    /** And it does not repoint the caller's existing row at another account either. */
    @Test
    void aWriteCannotRepointTheCallersOwnProfileAtAnotherAccount() throws Exception {
        storedCallerProfile();

        putOwnProfile(dtoBody("\"accountId\":\"somebody-else\",\"firstName\":\"Adwoa\"")).andExpect(status().isOk());

        Profile after = reloadCaller();
        assertThat(after.getAccountId()).isEqualTo(CALLER_ACCOUNT);
        assertThat(after.getFirstName()).as("the control: the request did land").isEqualTo("Adwoa");
    }

    /**
     * Two callers, two rows, within one test — so "own-scoped" is asserted against a concurrent
     * neighbour rather than against an empty collection.
     */
    @Test
    void twoCallersWriteTwoSeparateProfiles() throws Exception {
        restMockMvc
            .perform(
                put(OWN_PROFILE_URL)
                    .with(gatewayUser("own-profile-other", "ROLE_USER"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(dtoBody("\"firstName\":\"Kwame\""))
            )
            .andExpect(status().isOk());

        putOwnProfile(dtoBody("\"firstName\":\"Ama\"")).andExpect(status().isOk());

        assertThat(profileRepository.findAll()).hasSize(2);
        assertThat(reloadCaller().getFirstName()).isEqualTo("Ama");
        assertThat(profileRepository.findByAccountId(accountIdFor("own-profile-other")).orElseThrow().getFirstName()).isEqualTo("Kwame");
    }

    // ---------------------------------------------------------------------------------------------
    // Fields the write path does not apply: dropped at the service, or ignored at the bind
    // ---------------------------------------------------------------------------------------------

    /**
     * ⛔ <b>{@code status} is parsed on every body and never applied.</b>
     *
     * <p>{@code status} is the one protected field the DTO declares, and the mapper makes it
     * wire-mandatory — but {@code applyProvidedFields} never copies it, so the value sent cannot
     * reach the row. An applicant who could write it would approve their own credential review:
     * every legal move between {@code ProfileStatus} values is a {@code ROLE_ADMIN} transition that
     * checks {@code LEGAL_TRANSITIONS} and appends an {@code OnboardingEvent}, and this endpoint
     * checks nothing and appends nothing. The probe is a value no transition could reach from a
     * fresh profile.
     */
    @Test
    void statusInTheBodyIsDroppedRatherThanApplied() throws Exception {
        storedCallerProfile();

        putOwnProfile("{\"birthDate\":\"1990-01-01\",\"sex\":\"FEMALE\",\"cardType\":\"GHANACARD\",\"status\":\"APPROVED\"}").andExpect(
            status().isOk()
        );

        assertThat(reloadCaller().getStatus()).as("the caller's APPROVED must never land").isNull();
    }

    /**
     * Assigning yourself a discipline belongs to the organisation endpoint
     * ({@code PUT /api/professional-application/{id}/organization}, {@code ROLE_ADMIN}).
     * {@code specialtyCategoryId} is not a {@code ProfileDTO} field, so here it is an unknown key —
     * ignored at the bind, with nothing stored and nothing said.
     */
    @Test
    void specialtyCategoryIdIsIgnoredOnTheCallersOwnProfile() throws Exception {
        storedCallerProfile();

        putOwnProfile(dtoBody("\"specialtyCategoryId\":\"some-category\"")).andExpect(status().isOk());

        assertThat(reloadCaller().getSpecialtyCategoryId()).isNull();
    }

    /** As is filing yourself into a team — {@code teamIds} is likewise not a DTO field. */
    @Test
    void teamIdsAreIgnoredOnTheCallersOwnProfile() throws Exception {
        storedCallerProfile();

        putOwnProfile(dtoBody("\"teamIds\":[\"some-team\"]")).andExpect(status().isOk());

        assertThat(reloadCaller().getTeamIds()).isEmpty();
    }

    /**
     * The three push preferences have their own endpoint ({@code PUT /api/notifications/preferences},
     * also own-scoped), which stays their only writer: none of the three is a {@code ProfileDTO}
     * field, so a body naming one is ignored here rather than refused. One writer per field is what
     * keeps the defaults in one place — {@code pushShowSenderName} defaults OFF while the other two
     * default ON.
     */
    @Test
    void pushMessagesEnabledIsIgnoredOnTheCallersOwnProfile() throws Exception {
        storedCallerProfile();

        putOwnProfile(dtoBody("\"pushMessagesEnabled\":false")).andExpect(status().isOk());

        assertThat(reloadCaller().getPushMessagesEnabled()).isNull();
    }

    @Test
    void pushComplianceEnabledIsIgnoredOnTheCallersOwnProfile() throws Exception {
        storedCallerProfile();

        putOwnProfile(dtoBody("\"pushComplianceEnabled\":false")).andExpect(status().isOk());

        assertThat(reloadCaller().getPushComplianceEnabled()).isNull();
    }

    @Test
    void pushShowSenderNameIsIgnoredOnTheCallersOwnProfile() throws Exception {
        storedCallerProfile();

        putOwnProfile(dtoBody("\"pushShowSenderName\":true")).andExpect(status().isOk());

        assertThat(reloadCaller().getPushShowSenderName()).isNull();
    }

    /**
     * On the create there is no stored row, and a body naming {@code status} still cannot plant one:
     * the row is created, the named value is dropped, and the row's {@code status} is whatever the
     * server decides — here nothing, since the body is far from complete.
     */
    @Test
    void aStatusOnTheCreateIsDroppedAndTheRowIsCreatedWithoutIt() throws Exception {
        assertThat(profileRepository.findByAccountId(CALLER_ACCOUNT)).isEmpty();

        putOwnProfile(
            "{\"birthDate\":\"1990-01-01\",\"sex\":\"FEMALE\",\"cardType\":\"GHANACARD\",\"status\":\"APPROVED\",\"firstName\":\"Ama\"}"
        ).andExpect(status().isOk());

        Profile created = reloadCaller();
        assertThat(created.getStatus()).as("the caller's APPROVED must not survive the create either").isNull();
        assertThat(created.getFirstName()).isEqualTo("Ama");
    }

    /**
     * A read-modify-write client is not punished for carrying the protected fields back unchanged.
     *
     * <p>The wizard reads the profile, edits one pane and sends the document back; it names
     * {@code status} at whatever the server set (a valid member, so the mapper parses it), and the
     * fields the DTO does not declare — {@code specialtyCategoryId}, {@code teamIds}, the audit
     * fields — ride along as unknown keys and are ignored. The stored row must keep all of them.
     *
     * <p>The stored row carries the four wire-mandatory fields before the round trip, because a
     * read-back document lacking any of them cannot be sent back at all — the mapper parses them
     * unconditionally.
     */
    @Test
    void aRoundTrippedDocumentThatChangesNothingProtectedIsNotRefused() throws Exception {
        Profile stored = storedCallerProfile();
        stored.setBirthDate(LocalDate.of(1990, 1, 1));
        stored.setSex(Sex.FEMALE);
        stored.setCardType(DocumentType.GHANACARD);
        stored.setStatus(ProfileStatus.CREDENTIAL_REVIEW);
        stored.setSpecialtyCategoryId("cat-1");
        stored.setTeamIds(List.of("team-1"));
        profileRepository.save(stored);

        // The caller's own read, which is the body a real client would be sending back — not one
        // built in the test, which would be a different document.
        String readBack = restMockMvc.perform(get(OWN_PROFILE_URL)).andReturn().getResponse().getContentAsString();

        putOwnProfile(readBack.replace("\"firstName\":\"Ama\"", "\"firstName\":\"Adwoa\"")).andExpect(status().isOk());

        Profile after = reloadCaller();
        assertThat(after.getFirstName()).isEqualTo("Adwoa");
        assertThat(after.getStatus()).isEqualTo(ProfileStatus.CREDENTIAL_REVIEW);
        assertThat(after.getSpecialtyCategoryId()).isEqualTo("cat-1");
        assertThat(after.getTeamIds()).containsExactly("team-1");
    }

    /**
     * A write that never mentions {@code contacts} is not a write that cleared them — the
     * {@code teamIds} trap, one field over.
     *
     * <p>{@code Profile.contacts} is deliberately <b>not</b> initialised to an empty list, unlike
     * {@code teamIds}, and {@code EmergencyContactMapper.toEntityList(null)} stays null. Were either
     * an empty list, {@code provided.getContacts()} would never be null and every partial write
     * would empty the clinician's next of kin. This is the case that fails if somebody "tidies"
     * the field by giving it an initialiser.
     */
    @Test
    void anUnmentionedContactsListIsNotAChange() throws Exception {
        Profile stored = storedCallerProfile();
        stored.setContacts(List.of(new EmergencyContact().name("Efua Mensah").relationship("sister").phone("+233200000000")));
        profileRepository.save(stored);

        putOwnProfile(dtoBody("\"phoneNumber\":\"+233300000000\"")).andExpect(status().isOk());

        Profile after = reloadCaller();
        assertThat(after.getContacts()).as("a write that never mentioned contacts must not empty them").hasSize(1);
        assertThat(after.getPhoneNumber()).isEqualTo("+233300000000");
    }

    /** The same for {@code address}, the other embedded object, since it is the same guard. */
    @Test
    void anUnmentionedAddressIsNotAChange() throws Exception {
        Profile stored = storedCallerProfile();
        stored.setAddress(new Address().streetAddress("12 Oxford St").city("Accra").region("Greater Accra").country("Ghana"));
        profileRepository.save(stored);

        putOwnProfile(dtoBody("\"phoneNumber\":\"+233300000000\"")).andExpect(status().isOk());

        assertThat(reloadCaller().getAddress()).isNotNull();
        assertThat(reloadCaller().getAddress().getStreetAddress()).isEqualTo("12 Oxford St");
    }

    /**
     * ⚠ <b>The retired singular name no longer writes into the list: {@code emergencyContact} is not
     * a {@code ProfileDTO} field, so it is an unknown key and is ignored.</b>
     *
     * <p>Pinned because <b>both shipped clients still send it</b> — F8 re-pointed {@code web/}'s
     * {@code OnboardingProfileDto} and {@code mobile/}'s {@code me.page.ts} at this path without
     * renaming the field. A client saving next of kin through the singular key now gets a 200 with
     * nothing stored, which is exactly the shape T6 has to resolve: either the clients move to
     * {@code contacts[]} or the DTO grows the alias.
     */
    @Test
    void theRetiredSingularNameIsIgnoredOnTheWireHere() throws Exception {
        putOwnProfile(dtoBody("\"emergencyContact\":{\"name\":\"Efua Mensah\",\"relationship\":\"sister\"}")).andExpect(status().isOk());

        assertThat(reloadCaller().getContacts()).as("an unknown key stores nothing — the singular alias is gone").isNullOrEmpty();
    }

    /** A syntactically unreadable body is still a 400 from the message converter, not a 500. */
    @Test
    void anUnreadableDocumentIsRefused() throws Exception {
        putOwnProfile("{\"firstName\":").andExpect(status().isBadRequest());
    }

    /**
     * An unparseable {@code birthDate} is a 500: the DTO binds it as a plain string, so the converter
     * has nothing to refuse, and {@code ProfileMapper.toEntity}'s {@code LocalDate.parse} is what
     * throws — after binding, outside any handler that could answer 400.
     */
    @Test
    void anUnparseableBirthDateAnswersServerError() throws Exception {
        putOwnProfile("{\"birthDate\":\"not-a-date\",\"sex\":\"FEMALE\",\"cardType\":\"GHANACARD\",\"status\":\"CREATED\"}").andExpect(
            status().is5xxServerError()
        );
    }

    // ---------------------------------------------------------------------------------------------
    // PROFILE_COMPLETED (F1) — the server stamps it, the client never sends it
    // ---------------------------------------------------------------------------------------------
    //
    // profile.md § "Step 2 — Create the profile": "Set profile.status to PROFILE_COMPLETED when
    // every field is provided." "Every field" is the owner's literal, nested reading: 39 values.
    // ProfileCompletenessTest enumerates them one withheld at a time; what these cases hold is the
    // HTTP half — that the stamp happens on the write, that it happens ACROSS several panes rather
    // than only on a single complete body, and that the caller cannot ask for it.
    //
    // ⚠ middleNames is one of the 39 and the wire cannot deliver it — ProfileMapper.toEntity never
    // copies it — so completeness is only reachable for a row that already holds one. Each stamp
    // case pre-seeds it through the repository for that reason.

    /**
     * middleNames pre-seeded through the repository: required by {@code ProfileCompleteness},
     * undeliverable by the wire. See the section comment.
     */
    private void seedCallerRowWithMiddleNames() {
        profileRepository.save(new Profile().accountId(CALLER_ACCOUNT).middleNames("Nana Yaa"));
    }

    /**
     * A write that completes the stored row is stamped {@code PROFILE_COMPLETED}, in the response
     * and in the stored row. The body's own {@code status} (CREATED) is dropped; the stamp is the
     * server's.
     */
    @Test
    void aWriteThatCompletesTheStoredRowIsStampedProfileCompleted() throws Exception {
        seedCallerRowWithMiddleNames();

        putOwnProfile(completeProfileJson()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROFILE_COMPLETED"));

        assertThat(reloadCaller().getStatus()).isEqualTo(ProfileStatus.PROFILE_COMPLETED);
    }

    /**
     * ⭐ <b>The stamp is evaluated against the STORED ROW after the merge, not against the request
     * body</b> — which is the only reading that can work at all.
     *
     * <p>{@code profile.md} specifies a dialog panel per step, so no single body a wizard sends is
     * ever complete. A predicate run over the body would therefore stamp nothing, ever, and the
     * endpoint would satisfy its own test only when a client posted the whole document in one go —
     * which is the shape the specification rules out. Two writes here, each incomplete, completing
     * the profile between them.
     */
    @Test
    void thePanesCompleteTheProfileBetweenThemAndTheLastOneStampsIt() throws Exception {
        seedCallerRowWithMiddleNames();

        putOwnProfile("{" + CORE_FIELDS_FRAGMENT + "}").andExpect(status().isOk());
        assertThat(reloadCaller().getStatus()).as("the contacts pane has not run yet").isNull();

        putOwnProfile(dtoBody(CONTACTS_FRAGMENT)).andExpect(status().isOk());

        assertThat(reloadCaller().getStatus()).isEqualTo(ProfileStatus.PROFILE_COMPLETED);
    }

    /**
     * ⚠ <b>One contact is not enough</b> — {@code profile.md} step 2 requires at least two (F2).
     *
     * <p>Asserted here as well as in {@code ProfileCompletenessTest} because it is the requirement
     * the shipped wizard violated: the server's predicate was an {@code anyMatch} and accepted one,
     * and the only mention of "two" anywhere in this repository was a comment.
     */
    @Test
    void oneCompleteContactDoesNotCompleteTheProfile() throws Exception {
        seedCallerRowWithMiddleNames();

        putOwnProfile("{" + CORE_FIELDS_FRAGMENT + "," + oneContactFragment() + "}").andExpect(status().isOk());

        assertThat(reloadCaller().getStatus()).as("profile.md requires at least two emergency contacts").isNull();
    }

    /**
     * {@code title} is <b>not</b> required, and that is deliberate rather than an oversight.
     *
     * <p>It is absent from {@code profile.md}'s Profile model table, even though the page header
     * renders {@code profile.title}. The owner's enumeration of the 39 excludes it by name, so a
     * profile with no title is complete — and this case is what fails if somebody "finishes" the
     * predicate by adding every field the class happens to declare. (The wire could not deliver one
     * anyway: {@code ProfileMapper} never copies {@code title} either.)
     */
    @Test
    void titleIsNotOneOfTheRequiredFields() throws Exception {
        seedCallerRowWithMiddleNames();

        putOwnProfile(completeProfileJson()).andExpect(status().isOk());

        assertThat(reloadCaller().getTitle()).as("the complete body deliberately omits it").isNull();
        assertThat(reloadCaller().getStatus()).isEqualTo(ProfileStatus.PROFILE_COMPLETED);
    }

    /**
     * ⛔ <b>A complete body that also NAMES {@code status} does not get to choose it.</b>
     *
     * <p>The caller's {@code APPROVED} is parsed by the mapper (it must be a valid member) and
     * dropped by {@code applyProvidedFields}; the row then completes, so what lands is the server's
     * {@code PROFILE_COMPLETED} — the stamp, not the smuggled value. An applicant who could write
     * {@code status} would approve their own credential review, and a body that happens to be
     * complete is the easiest place to smuggle one.
     */
    @Test
    void aCompleteWriteThatAlsoNamesStatusDoesNotStoreTheNamedValue() throws Exception {
        seedCallerRowWithMiddleNames();

        putOwnProfile(completeProfileJson().replace("\"status\":\"CREATED\"", "\"status\":\"APPROVED\"")).andExpect(status().isOk());

        assertThat(reloadCaller().getStatus())
            .as("the server's stamp lands, never the caller's value")
            .isEqualTo(ProfileStatus.PROFILE_COMPLETED);
    }

    // ---------------------------------------------------------------------------------------------
    // sex and cardType are enums (F9) — an invalid value is never stored, but the answer is now a
    // 500: the DTO binds them as plain strings, so the converter has nothing to refuse, and
    // ProfileMapper's valueOf is what throws, after binding and outside any 400-answering handler.
    // ---------------------------------------------------------------------------------------------

    /** {@code {"sex":"banana"}} stored and answered 200 until {@link net.jojoaddison.domain.enumeration.Sex} existed. */
    @Test
    void aSexOutsideTheEnumerationAnswersServerError() throws Exception {
        putOwnProfile(
            "{\"birthDate\":\"1990-01-01\",\"cardType\":\"GHANACARD\",\"status\":\"CREATED\",\"firstName\":\"Ama\",\"sex\":\"banana\"}"
        ).andExpect(status().is5xxServerError());

        assertThat(profileRepository.findByAccountId(CALLER_ACCOUNT)).as("a failed write stores nothing").isEmpty();
    }

    /** And {@code {"cardType":"loyalty card"}}, which {@code profile.md} types by PersonalDocumentType. */
    @Test
    void aCardTypeOutsideTheEnumerationAnswersServerError() throws Exception {
        putOwnProfile(
            "{\"birthDate\":\"1990-01-01\",\"sex\":\"FEMALE\",\"status\":\"CREATED\",\"firstName\":\"Ama\",\"cardType\":\"loyalty card\"}"
        ).andExpect(status().is5xxServerError());

        assertThat(profileRepository.findByAccountId(CALLER_ACCOUNT)).isEmpty();
    }

    /**
     * ⚠ <b>Case matters on the wire.</b> {@code "female"} fails where {@code "FEMALE"} is accepted —
     * {@code Sex.valueOf} is exact — which is why {@code ProfileEnumValueMigration} exists for what
     * was already stored; the quality database held exactly one such row.
     */
    @Test
    void aMemberNameInTheWrongCaseAnswersServerError() throws Exception {
        putOwnProfile(
            "{\"birthDate\":\"1990-01-01\",\"cardType\":\"GHANACARD\",\"status\":\"CREATED\",\"firstName\":\"Ama\",\"sex\":\"female\"}"
        ).andExpect(status().is5xxServerError());
    }

    /** The members do round-trip, which is the control for the three failures above. */
    @Test
    void bothEnumValuesRoundTrip() throws Exception {
        putOwnProfile("{\"birthDate\":\"1990-01-01\",\"status\":\"CREATED\",\"sex\":\"MALE\",\"cardType\":\"GHANACARD\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sex").value("MALE"))
            .andExpect(jsonPath("$.cardType").value("GHANACARD"));

        Profile after = reloadCaller();
        assertThat(after.getSex()).isEqualTo(Sex.MALE);
        assertThat(after.getCardType()).isEqualTo(DocumentType.GHANACARD);
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * The contacts half of {@link #completeProfileJson()}, kept separate so a case can swap it out
     * without rebuilding the other values — which is what makes "complete but for X" assertable.
     */
    private static final String CONTACTS_FRAGMENT =
        "\"contacts\":[" +
        contactFragment("Efua Mensah", "sister", "efua@example.com", "+233200000001") +
        "," +
        contactFragment("Kojo Mensah", "brother", "kojo@example.com", "+233200000002") +
        "]";

    /**
     * The wire-deliverable half of what {@code profile.md} step 2 requires of {@code Profile} itself,
     * plus the full {@code address} — DTO field names ({@code mobileNumber}, not the entity's
     * {@code mobilePhone}). Includes {@link #MANDATORY_WIRE_FIELDS}; {@code middleNames} is in the
     * required 39 and NOT here, because the mapper drops it — the stamp cases pre-seed it instead.
     */
    private static final String CORE_FIELDS_FRAGMENT =
        MANDATORY_WIRE_FIELDS +
        ",\"firstName\":\"Ama\",\"lastName\":\"Boateng\"," +
        "\"mobileNumber\":\"+233200000000\",\"phoneNumber\":\"+233300000000\",\"email\":\"ama@example.com\"," +
        "\"cardNumber\":\"GHA-1\"," +
        "\"address\":" +
        addressFragment("GA-123-4567", "12 Oxford St", "Osu", "Accra", "Ayawaso East");

    /**
     * All the wire can deliver of the 39 values {@code profile.md} step 2 requires: 9 of the 10
     * {@code Profile} fields (middleNames is undeliverable), the 7 {@code address} fields, and 11 on
     * each of two contacts.
     *
     * <p>⛔ <b>{@code title} is deliberately absent</b> — see {@link #titleIsNotOneOfTheRequiredFields}.
     * So are {@code id} and {@code accountId}, neither of which is a user input. {@code status} is
     * present because the mapper requires it on every body; the service drops it.
     */
    private static String completeProfileJson() {
        return "{" + CORE_FIELDS_FRAGMENT + "," + CONTACTS_FRAGMENT + "}";
    }

    /** One complete contact, for the case that asserts one is not two. */
    private static String oneContactFragment() {
        return "\"contacts\":[" + contactFragment("Efua Mensah", "sister", "efua@example.com", "+233200000001") + "]";
    }

    /** A contact in DTO shape — {@code phoneNumber}, where the entity says {@code phone}. */
    private static String contactFragment(String name, String relationship, String email, String phone) {
        return (
            "{\"name\":\"" +
            name +
            "\",\"relationship\":\"" +
            relationship +
            "\"," +
            "\"email\":\"" +
            email +
            "\",\"phoneNumber\":\"" +
            phone +
            "\"," +
            "\"address\":" +
            addressFragment("GA-999-0000", "3 High St", "Bantama", "Kumasi", "Bantama") +
            "}"
        );
    }

    /** The seven {@code Address} input fields — all of them, since step 2 requires all of them. */
    private static String addressFragment(String digital, String street, String town, String city, String district) {
        return (
            "{\"digitalAddress\":\"" +
            digital +
            "\",\"streetAddress\":\"" +
            street +
            "\"," +
            "\"town\":\"" +
            town +
            "\",\"city\":\"" +
            city +
            "\",\"district\":\"" +
            district +
            "\"," +
            "\"region\":\"Greater Accra\",\"country\":\"Ghana\"}"
        );
    }
}
