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
 * <h2>What this class is for, as distinct from its neighbours</h2>
 *
 * <p>{@code ClinicalAuthorityMatrixIT} holds <em>who may call it</em>. {@code ProfilePatchFieldCoverageIT}
 * holds <em>which fields any partial write applies or refuses</em>, by reflecting over the entity.
 * This class holds the properties that are specific to the endpoint being <b>own-scoped</b> and
 * <b>partial</b>: that a pane saving its own slice does not blank the panes before it, that the body
 * cannot select whose row is written, and that the refusals are not weaker for the row being the
 * caller's.
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
     * {@code status} is readable here and refused on the write, which is one field answering two
     * different questions.
     *
     * <p>{@code profile.md} renders {@code profile.status} in the page header, so the read has to
     * carry it; {@code ProfileFieldOwnership} refuses it on the write because the state machine owns
     * it. See {@link #statusIsRefusedOnTheCallersOwnProfileToo}.
     */
    @Test
    void theReadCarriesStatusEvenThoughTheWriteRefusesIt() throws Exception {
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
     */
    @Test
    void aLaterWriteDoesNotBlankTheFieldsAnEarlierWriteSet() throws Exception {
        putOwnProfile(
            "{\"firstName\":\"Ama\",\"lastName\":\"Boateng\",\"birthDate\":\"1990-01-01\"," +
            "\"address\":{\"streetAddress\":\"12 Oxford St\",\"city\":\"Accra\",\"country\":\"Ghana\"}}"
        ).andExpect(status().isOk());

        // The next-of-kin pane. It knows nothing about the address pane and sends none of its fields.
        putOwnProfile("{\"contacts\":[{\"name\":\"Efua Mensah\",\"relationship\":\"sister\",\"phone\":\"+233200000000\"}]}").andExpect(
            status().isOk()
        );

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
     * call can repair because {@code accountId} is {@code READ_ONLY} over HTTP.
     */
    @Test
    void theFirstWriteCreatesTheProfileOwnedByTheCaller() throws Exception {
        assertThat(profileRepository.findByAccountId(CALLER_ACCOUNT)).isEmpty();

        putOwnProfile("{\"firstName\":\"Ama\"}").andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(CALLER_ACCOUNT));

        Profile created = reloadCaller();
        assertThat(created.getId()).as("step 3 keys off Profile.id, so the create has to produce one").isNotNull();
        assertThat(created.getFirstName()).isEqualTo("Ama");
    }

    /** A second write adopts the row rather than making a second one. */
    @Test
    void aSecondWriteAdoptsTheExistingRowRatherThanCreatingAnother() throws Exception {
        putOwnProfile("{\"firstName\":\"Ama\"}").andExpect(status().isOk());
        String id = reloadCaller().getId();

        putOwnProfile("{\"lastName\":\"Boateng\"}").andExpect(status().isOk());

        assertThat(profileRepository.findAll()).as("one account, one profile").hasSize(1);
        assertThat(reloadCaller().getId()).isEqualTo(id);
    }

    /**
     * {@code middleNames} round-trips, and <b>no applicant-facing write path copied it before</b>.
     *
     * <p>⛔ <b>THE HEADING OVERSTATES IT AND IS CORRECTED HERE (F6).</b> It read "no write path in
     * this service copied it before", and the admin {@code PATCH /api/profiles/&#123;id&#125;} did —
     * through {@code ProfileService.applyProvidedFields}, before T1 widened it. The true and
     * narrower claim: <b>no APPLICANT-FACING write path copied it</b>, because the retired
     * {@code upsertOwnProfile} omitted it, so the middle name the wizard collected was stored by
     * nothing on the one route a clinician had. A field unreachable from one direction is not a
     * field nothing could reach, and the difference is the whole of F6.
     *
     * <p>⭐ <b>The field name no longer diverges.</b> {@code profile.md}'s Profile model reads
     * {@code middleNames}, aligned to the code by the owner, so the paragraph that used to record a
     * {@code middleName}/{@code middleNames} divergence here describes nothing. ⚠ The document's
     * page-header row still renders the singular; that is the owner's document and is reported, not
     * edited.
     */
    @Test
    void middleNamesRoundTrips() throws Exception {
        putOwnProfile("{\"firstName\":\"Ama\",\"middleNames\":\"Nana Yaa\",\"lastName\":\"Boateng\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.middleNames").value("Nana Yaa"));

        assertThat(reloadCaller().getMiddleNames()).isEqualTo("Nana Yaa");

        restMockMvc.perform(get(OWN_PROFILE_URL)).andExpect(status().isOk()).andExpect(jsonPath("$.middleNames").value("Nana Yaa"));
    }

    /** At least two contacts, each with a structured address — what {@code profile.md} step 2 collects. */
    @Test
    void twoContactsWithStructuredAddressesRoundTrip() throws Exception {
        putOwnProfile(
            "{\"contacts\":[" +
            "{\"name\":\"Efua Mensah\",\"relationship\":\"sister\",\"phone\":\"+233200000000\"," +
            "\"address\":{\"streetAddress\":\"12 Oxford St\",\"city\":\"Accra\"}}," +
            "{\"name\":\"Kojo Mensah\",\"relationship\":\"brother\",\"phone\":\"+233200000001\"," +
            "\"address\":{\"streetAddress\":\"3 High St\",\"city\":\"Kumasi\"}}]}"
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts[1].address.city").value("Kumasi"));

        List<EmergencyContact> contacts = reloadCaller().getContacts();
        assertThat(contacts).hasSize(2);
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
     * <p>This is backlog.md item 54's account takeover asked of a new endpoint. Two things make it
     * impossible and the test asserts the outcome rather than either mechanism: {@code accountId} is
     * {@code @JsonProperty(READ_ONLY)} so Jackson drops it, and
     * {@code ProfileService.partialUpdateOwnProfile} sets it from the token regardless. The body
     * also carries {@code id} — the other identifier a caller might hope selects a row — and
     * {@code accountUid}, the field item 48 added and item 50 deleted, so an author who reinstates a
     * second account identifier finds a test that already names it.
     */
    @Test
    void aWriteNamingAnotherAccountCannotReachThatAccountsProfile() throws Exception {
        Profile victim = profileRepository.save(
            new Profile().accountId("victim-account").firstName("Kwame").cardNumber("GHA-VICTIM").lastName("Asante")
        );

        putOwnProfile(
            "{\"id\":\"" +
            victim.getId() +
            "\",\"accountId\":\"victim-account\",\"accountUid\":\"victim-account\"," +
            "\"firstName\":\"Attacker\",\"cardNumber\":\"GHA-FORGED\"}"
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

        putOwnProfile("{\"accountId\":\"somebody-else\",\"firstName\":\"Adwoa\"}").andExpect(status().isOk());

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
                    .content("{\"firstName\":\"Kwame\"}")
            )
            .andExpect(status().isOk());

        putOwnProfile("{\"firstName\":\"Ama\"}").andExpect(status().isOk());

        assertThat(profileRepository.findAll()).hasSize(2);
        assertThat(reloadCaller().getFirstName()).isEqualTo("Ama");
        assertThat(profileRepository.findByAccountId(accountIdFor("own-profile-other")).orElseThrow().getFirstName()).isEqualTo("Kwame");
    }

    // ---------------------------------------------------------------------------------------------
    // The refusals, which are not weaker for the row being the caller's own
    // ---------------------------------------------------------------------------------------------

    /**
     * Asserts the refusal, its status, that the offending field <b>and the endpoint that does own
     * it</b> are named in the body, and that nothing at all was written.
     *
     * <p>The {@code owningEndpoint} half is not decoration — it can only come from
     * {@code ProfileFieldOwnership.REFUSED_FIELDS}, so asserting it is what notices a map entry
     * deleted while its guard stays in place, which still refuses, still names the field, and renders
     * "…is set by null": a refusal that has forgotten where to send the caller.
     */
    private void assertRefusedOnOwnProfile(String field, String owningEndpoint, String json) throws Exception {
        storedCallerProfile();

        String body = putOwnProfile(json).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertThat(body).as("the refusal must name the field it refused, or it is backlog item 46 again").contains(field);
        assertThat(body)
            .as("and must name where the caller should go instead — a stale REFUSED_FIELDS entry renders 'is set by null'")
            .contains(owningEndpoint);
        assertThat(reloadCaller().getFirstName())
            .as("a refused write writes nothing at all, not even the fields it was allowed to write")
            .isEqualTo("Ama");
    }

    private static final String ORGANISATION_ENDPOINT = "PUT /api/onboarding/applications/{id}/organization";

    private static final String PREFERENCES_ENDPOINT = "PUT /api/notifications/preferences";

    private static final String ONBOARDING_TRANSITION_ENDPOINT = "PUT /api/onboarding/applications/{id}/decide";

    /**
     * ⛔ <b>{@code status} is the case that shows the refusals are not about disclosure.</b>
     *
     * <p>"It is my own row" is an argument about who may <em>see</em> a field. {@code status} is the
     * applicant's own field and an applicant who could write it <b>would approve their own credential
     * review</b> — every legal move between {@code ProfileStatus} values is a {@code ROLE_ADMIN}
     * transition that checks {@code LEGAL_TRANSITIONS} and appends an {@code OnboardingEvent}, and
     * this endpoint checks nothing and appends nothing. So own-scoped buys no leniency here at all,
     * and the probe is a value no transition could reach from a fresh profile.
     */
    @Test
    void statusIsRefusedOnTheCallersOwnProfileToo() throws Exception {
        assertRefusedOnOwnProfile("status", ONBOARDING_TRANSITION_ENDPOINT, "{\"status\":\"APPROVED\"}");
    }

    /** Assigning yourself a discipline is the organisation endpoint's, and it is {@code ROLE_ADMIN}. */
    @Test
    void specialtyCategoryIdIsRefusedOnTheCallersOwnProfileToo() throws Exception {
        assertRefusedOnOwnProfile("specialtyCategoryId", ORGANISATION_ENDPOINT, "{\"specialtyCategoryId\":\"some-category\"}");
    }

    /** As is filing yourself into a team. */
    @Test
    void teamIdsAreRefusedOnTheCallersOwnProfileToo() throws Exception {
        assertRefusedOnOwnProfile("teamIds", ORGANISATION_ENDPOINT, "{\"teamIds\":[\"some-team\"]}");
    }

    /**
     * The three push preferences have their own endpoint, which is also own-scoped — so here the
     * refusal is not about authority but about there being one writer per field.
     *
     * <p>It is the weaker of the arguments and it is still the right answer: two endpoints writing
     * one field is how the two disagree about defaults, and {@code pushShowSenderName} defaults OFF
     * while the other two default ON.
     */
    @Test
    void pushMessagesEnabledIsRefusedOnTheCallersOwnProfileToo() throws Exception {
        assertRefusedOnOwnProfile("pushMessagesEnabled", PREFERENCES_ENDPOINT, "{\"pushMessagesEnabled\":false}");
    }

    @Test
    void pushComplianceEnabledIsRefusedOnTheCallersOwnProfileToo() throws Exception {
        assertRefusedOnOwnProfile("pushComplianceEnabled", PREFERENCES_ENDPOINT, "{\"pushComplianceEnabled\":false}");
    }

    @Test
    void pushShowSenderNameIsRefusedOnTheCallersOwnProfileToo() throws Exception {
        assertRefusedOnOwnProfile("pushShowSenderName", PREFERENCES_ENDPOINT, "{\"pushShowSenderName\":true}");
    }

    /**
     * ⚠ <b>And on the create, where there is no stored row to compare against.</b>
     *
     * <p>The refusal asks "does this change the stored value", and on a first save there is no stored
     * value — so a naive implementation would compare the caller's {@code APPROVED} against
     * {@code null}, find no stored row to read, and throw or allow. The create is the <em>one</em>
     * moment at which somebody could plant a status the state machine never issued, so it must
     * refuse, and nothing may be created by the refused request.
     */
    @Test
    void aRefusedFieldIsRefusedOnTheCreateTooAndCreatesNothing() throws Exception {
        assertThat(profileRepository.findByAccountId(CALLER_ACCOUNT)).isEmpty();

        putOwnProfile("{\"firstName\":\"Ama\",\"status\":\"APPROVED\"}").andExpect(status().isBadRequest());

        assertThat(profileRepository.findByAccountId(CALLER_ACCOUNT))
            .as("a refused create must not leave a half-written row behind")
            .isEmpty();
    }

    /**
     * A read-modify-write client is not punished for carrying the protected fields back unchanged.
     *
     * <p>The refusal is on a <em>change</em>, not on the mention. The wizard reads the profile, edits
     * one pane and sends the document back; it names {@code status} at whatever the server set, and
     * refusing that would make the honest answer unusable.
     */
    @Test
    void aRoundTrippedDocumentThatChangesNothingProtectedIsNotRefused() throws Exception {
        Profile stored = storedCallerProfile();
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
     * {@code teamIds}. Were it initialised, {@code provided.getContacts()} would never be null and
     * every partial write would empty the clinician's next of kin. This is the case that fails if
     * somebody "tidies" the field by giving it an initialiser.
     */
    @Test
    void anUnmentionedContactsListIsNotAChange() throws Exception {
        Profile stored = storedCallerProfile();
        stored.setContacts(List.of(new EmergencyContact().name("Efua Mensah").relationship("sister").phone("+233200000000")));
        profileRepository.save(stored);

        putOwnProfile("{\"phoneNumber\":\"+233300000000\"}").andExpect(status().isOk());

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

        putOwnProfile("{\"phoneNumber\":\"+233300000000\"}").andExpect(status().isOk());

        assertThat(reloadCaller().getAddress()).isNotNull();
        assertThat(reloadCaller().getAddress().getStreetAddress()).isEqualTo("12 Oxford St");
    }

    /**
     * The retired singular name still writes into the list through this endpoint too.
     *
     * <p>⚠ <b>Both shipped clients do call {@code /api/profile} with it</b> since F8 re-pointed
     * {@code web/}'s {@code OnboardingProfileDto} and {@code mobile/}'s {@code me.page.ts} at this
     * path without renaming the field — this sentence read "none does yet" and that stopped being
     * true in the same change. The alias lives on {@code Profile} and therefore applies to every
     * endpoint that binds one. Pinned
     * here so that retiring it with T6 is a decision somebody makes rather than a surprise, and so
     * that the two endpoints cannot come to mean different things by the same field name.
     */
    @Test
    void theRetiredSingularNameStillWritesIntoTheListHereToo() throws Exception {
        putOwnProfile("{\"emergencyContact\":{\"name\":\"Efua Mensah\",\"relationship\":\"sister\"}}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts[0].name").value("Efua Mensah"));

        assertThat(reloadCaller().getContacts()).hasSize(1);
    }

    /** An unreadable body is a 400 rather than a 500. */
    @Test
    void anUnreadableDocumentIsRefused() throws Exception {
        putOwnProfile("{\"birthDate\":\"not-a-date\"}").andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------------------------------
    // PROFILE_COMPLETED (F1) — the server stamps it, the client never sends it
    // ---------------------------------------------------------------------------------------------
    //
    // profile.md § "Step 2 — Create the profile": "Set profile.status to PROFILE_COMPLETED when
    // every field is provided." NOTHING WROTE Profile.status AT ALL before F1 — a search for
    // PROFILE_COMPLETED found the enum declaration and four OnboardingService sites, every one of
    // them on ProfessionalApplication.status, which is a different field on a different document.
    //
    // "Every field" is the owner's literal, nested reading: 39 values. ProfileCompletenessTest
    // enumerates them one withheld at a time; what these cases hold is the HTTP half — that the
    // stamp happens on the write, that it happens ACROSS several panes rather than only on a single
    // complete body, and that the caller cannot ask for it.

    /**
     * A body providing all 39 values is stamped {@code PROFILE_COMPLETED}, in the response and in
     * the stored row.
     */
    @Test
    void aCompleteWriteIsStampedProfileCompleted() throws Exception {
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
        putOwnProfile(completeProfileJson().replace(CONTACTS_FRAGMENT, "\"phoneNumber\":\"+233300000000\"")).andExpect(status().isOk());
        assertThat(reloadCaller().getStatus()).as("the contacts pane has not run yet").isNull();

        putOwnProfile("{" + CONTACTS_FRAGMENT + "}").andExpect(status().isOk());

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
        putOwnProfile(completeProfileJson().replace(CONTACTS_FRAGMENT, oneContactFragment())).andExpect(status().isOk());

        assertThat(reloadCaller().getStatus()).as("profile.md requires at least two emergency contacts").isNull();
    }

    /**
     * {@code title} is <b>not</b> required, and that is deliberate rather than an oversight.
     *
     * <p>It is absent from {@code profile.md}'s Profile model table, even though the page header
     * renders {@code profile.title}. The owner's enumeration of the 39 excludes it by name, so a
     * profile with no title is complete — and this case is what fails if somebody "finishes" the
     * predicate by adding every field the class happens to declare.
     */
    @Test
    void titleIsNotOneOfTheRequiredFields() throws Exception {
        putOwnProfile(completeProfileJson()).andExpect(status().isOk());

        assertThat(reloadCaller().getTitle()).as("the complete body deliberately omits it").isNull();
        assertThat(reloadCaller().getStatus()).isEqualTo(ProfileStatus.PROFILE_COMPLETED);
    }

    /**
     * ⛔ <b>A complete body that also NAMES {@code status} is refused, not stamped.</b>
     *
     * <p>The two halves of the rule read from both ends: the caller cannot set {@code status}
     * ({@code ProfileFieldOwnership}) and the server always does ({@code ProfileCompleteness}). An
     * applicant who could write it would approve their own credential review, and a body that
     * happens to be complete is the easiest place to smuggle one.
     */
    @Test
    void aCompleteWriteThatAlsoNamesStatusIsStillRefused() throws Exception {
        putOwnProfile(completeProfileJson().replace("{\"firstName\"", "{\"status\":\"APPROVED\",\"firstName\"")).andExpect(
            status().isBadRequest()
        );

        assertThat(profileRepository.findByAccountId(CALLER_ACCOUNT)).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // sex and cardType are enums (F9) — an invalid value is refused rather than stored
    // ---------------------------------------------------------------------------------------------

    /** {@code {"sex":"banana"}} stored and answered 200 until {@link net.jojoaddison.domain.enumeration.Sex} existed. */
    @Test
    void aSexOutsideTheEnumerationIsRefused() throws Exception {
        putOwnProfile("{\"firstName\":\"Ama\",\"sex\":\"banana\"}").andExpect(status().isBadRequest());

        assertThat(profileRepository.findByAccountId(CALLER_ACCOUNT)).as("a refused write stores nothing").isEmpty();
    }

    /** And {@code {"cardType":"loyalty card"}}, which {@code profile.md} types by PersonalDocumentType. */
    @Test
    void aCardTypeOutsideTheEnumerationIsRefused() throws Exception {
        putOwnProfile("{\"firstName\":\"Ama\",\"cardType\":\"loyalty card\"}").andExpect(status().isBadRequest());

        assertThat(profileRepository.findByAccountId(CALLER_ACCOUNT)).isEmpty();
    }

    /**
     * ⚠ <b>Case matters on the wire.</b> {@code "female"} is refused where {@code "FEMALE"} is
     * accepted, which is why {@code ProfileEnumValueMigration} exists for what was already stored —
     * the quality database held exactly one such row.
     */
    @Test
    void aMemberNameInTheWrongCaseIsRefusedOnTheWire() throws Exception {
        putOwnProfile("{\"firstName\":\"Ama\",\"sex\":\"female\"}").andExpect(status().isBadRequest());
    }

    /** The members do round-trip, which is the control for the three refusals above. */
    @Test
    void bothEnumValuesRoundTrip() throws Exception {
        putOwnProfile("{\"sex\":\"MALE\",\"cardType\":\"GHANACARD\"}")
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
     * without rebuilding the other 17 values — which is what makes "complete but for X" assertable.
     */
    private static final String CONTACTS_FRAGMENT =
        "\"contacts\":[" +
        contactFragment("Efua Mensah", "sister", "efua@example.com", "+233200000001") +
        "," +
        contactFragment("Kojo Mensah", "brother", "kojo@example.com", "+233200000002") +
        "]";

    /**
     * All 39 values {@code profile.md} step 2 requires: the 10 {@code Profile} fields, the 7
     * {@code address} fields, and 11 on each of two contacts.
     *
     * <p>⛔ <b>{@code title} is deliberately absent</b> — see {@link #titleIsNotOneOfTheRequiredFields}.
     * So are {@code id}, {@code accountId} and {@code status}, none of which is a user input.
     */
    private static String completeProfileJson() {
        return (
            "{\"firstName\":\"Ama\",\"middleNames\":\"Nana Yaa\",\"lastName\":\"Boateng\"," +
            "\"birthDate\":\"1990-01-01\",\"sex\":\"FEMALE\",\"mobilePhone\":\"+233200000000\"," +
            "\"phoneNumber\":\"+233300000000\",\"email\":\"ama@example.com\"," +
            "\"cardType\":\"GHANACARD\",\"cardNumber\":\"GHA-1\"," +
            "\"address\":" +
            addressFragment("GA-123-4567", "12 Oxford St", "Osu", "Accra", "Ayawaso East") +
            "," +
            CONTACTS_FRAGMENT +
            "}"
        );
    }

    /** One complete contact, for the case that asserts one is not two. */
    private static String oneContactFragment() {
        return "\"contacts\":[" + contactFragment("Efua Mensah", "sister", "efua@example.com", "+233200000001") + "]";
    }

    private static String contactFragment(String name, String relationship, String email, String phone) {
        return (
            "{\"name\":\"" +
            name +
            "\",\"relationship\":\"" +
            relationship +
            "\"," +
            "\"email\":\"" +
            email +
            "\",\"phone\":\"" +
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
