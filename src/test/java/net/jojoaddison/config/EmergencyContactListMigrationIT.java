package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.EmergencyContact;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.repository.ProfileRepository;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * {@link EmergencyContactListMigration} — the singular {@code emergency_contact} becomes a
 * {@code contacts} list, and its line of text becomes a structured address (profile.md, T1).
 *
 * <p>This constructs the runner and calls it directly, for the reason {@code ShiftTypeMigrationIT}
 * records: Spring Boot does not invoke {@link org.springframework.boot.ApplicationRunner} beans under
 * {@code @SpringBootTest} — it is {@code SpringApplication.run} that calls them — so the bean in the
 * context never fires here whatever profile it registers on.
 *
 * <p><b>The fixtures are raw {@link Document}s and necessarily so.</b> The old shape cannot be
 * expressed through the entity any more: {@code EmergencyContact.address} is an
 * {@code Address} where the stored value is a {@code String}, which is precisely the state the
 * migration exists to leave behind. A migration cannot be set up in the vocabulary it replaces.
 *
 * <p>⚠ <b>The last test is the one that matters most and it is about the entity, not the document.</b>
 * Asserting the BSON only would pass against a shape Spring Data cannot read back — which is the
 * failure mode with real consequences, since every unmigrated row would then throw on the first read
 * rather than on the migration.
 */
@IntegrationTest
class EmergencyContactListMigrationIT {

    private static final String COLLECTION = "profile";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ProfileRepository profileRepository;

    private EmergencyContactListMigration migration;

    @BeforeEach
    void setUp() {
        mongoTemplate.remove(new Query(), COLLECTION);
        migration = new EmergencyContactListMigration(mongoTemplate);
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.remove(new Query(), COLLECTION);
    }

    /** A document in the shape the collection held before this change. */
    private void insertLegacy(String accountId, Document emergencyContact) {
        mongoTemplate.insert(
            new Document("account_id", accountId).append("first_name", "Ama").append("emergency_contact", emergencyContact),
            COLLECTION
        );
    }

    private Document raw(String accountId) {
        return mongoTemplate.findOne(new Query(Criteria.where("account_id").is(accountId)), Document.class, COLLECTION);
    }

    // ---------------------------------------------------------------------------------------------
    // The remap
    // ---------------------------------------------------------------------------------------------

    /**
     * ⛔ <b>It remaps rather than deletes</b>, which is {@code ShiftTypeMigration}'s side of the
     * argument and not {@code AngelDutyRoleMigration}'s.
     *
     * <p>Somebody named a person to contact in an emergency. That is a fact, it is still true, and
     * only the shape it is written in has changed — unlike an angel's duty-roster row, where
     * remapping would have invented a cover arrangement that never existed. Dropping it would
     * silently un-complete the {@code nextOfKin} requirement for every clinician who had satisfied
     * it, and the {@code ACTIVE} gate refuses activation unless every requirement is satisfied, so
     * the consequence is blocked activations rather than a wrong percentage.
     */
    @Test
    void theSingularContactBecomesAOneElementList() {
        insertLegacy(
            "legacy-1",
            new Document("name", "Efua Mensah")
                .append("relationship", "sister")
                .append("phone", "+233200000000")
                .append("email", "efua@example.com")
        );

        migration.run(null);

        Document after = raw("legacy-1");
        assertThat(after).isNotNull();
        assertThat(after.get("emergency_contact")).as("the retired key is removed, not left beside the new one").isNull();

        @SuppressWarnings("unchecked")
        List<Document> contacts = (List<Document>) after.get("contacts");
        assertThat(contacts).hasSize(1);
        assertThat(contacts.get(0).getString("name")).isEqualTo("Efua Mensah");
        assertThat(contacts.get(0).getString("relationship")).isEqualTo("sister");
        assertThat(contacts.get(0).getString("phone")).isEqualTo("+233200000000");
        assertThat(contacts.get(0).getString("email")).isEqualTo("efua@example.com");
    }

    /**
     * The line of text lands in {@code street_address}.
     *
     * <p>That is the field the wizard's street line writes and the one
     * {@code OnboardingService.addressComplete} reads, so a one-line address stays legible where a
     * reader would look for it. Guessing which of nine fields each comma-separated fragment belonged
     * to would manufacture structure nobody entered.
     */
    @Test
    void theLineOfTextBecomesTheStreetAddress() {
        insertLegacy("legacy-2", new Document("name", "Efua Mensah").append("address", "12 Oxford St, Accra"));

        migration.run(null);

        @SuppressWarnings("unchecked")
        List<Document> contacts = (List<Document>) raw("legacy-2").get("contacts");
        Document address = contacts.get(0).get("address", Document.class);
        assertThat(address).isNotNull();
        assertThat(address.getString("street_address")).isEqualTo("12 Oxford St, Accra");
        assertThat(address.get("_id")).as("no identifier is minted for a field nobody reads").isNull();
    }

    /**
     * A blank address leaves no {@code Address} at all, rather than one with nine nulls.
     *
     * <p>"Nobody gave an address" and "an address with every field empty" should not read the same,
     * and only the first is true of these rows — {@code addressComplete} would answer false either
     * way, but a reader looking at the document would be told something that was never entered.
     */
    @Test
    void aBlankAddressLeavesNoAddressAtAll() {
        insertLegacy("legacy-3", new Document("name", "Efua Mensah").append("address", "   "));

        migration.run(null);

        @SuppressWarnings("unchecked")
        List<Document> contacts = (List<Document>) raw("legacy-3").get("contacts");
        assertThat(contacts.get(0).containsKey("address")).isFalse();
        assertThat(contacts.get(0).getString("name")).as("the contact itself survives a blank address").isEqualTo("Efua Mensah");
    }

    /** A contact with no address member at all is carried through untouched. */
    @Test
    void aContactWithNoAddressIsCarriedThrough() {
        insertLegacy("legacy-4", new Document("name", "Efua Mensah").append("phone", "+233200000000"));

        migration.run(null);

        @SuppressWarnings("unchecked")
        List<Document> contacts = (List<Document>) raw("legacy-4").get("contacts");
        assertThat(contacts).hasSize(1);
        assertThat(contacts.get(0).containsKey("address")).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // Idempotence and what it must not touch
    // ---------------------------------------------------------------------------------------------

    /**
     * Running it twice changes nothing the first run did not.
     *
     * <p>Idempotent by construction rather than by a marker document: the query matches rows that
     * <em>have</em> {@code emergency_contact} and the update unsets the key, so a second run matches
     * nothing. There is no "has this run" flag that could be wrong, which is what lets deployments
     * roll forward and restart freely.
     */
    @Test
    void runningItTwiceChangesNothing() {
        insertLegacy("legacy-5", new Document("name", "Efua Mensah").append("address", "12 Oxford St"));

        migration.run(null);
        Document afterFirst = raw("legacy-5");

        migration.run(null);
        Document afterSecond = raw("legacy-5");

        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    /**
     * ⭐ <b>A row carrying BOTH names keeps the newer value AND loses the stale key (F-D).</b>
     *
     * <p>That combination can genuinely occur. {@code Profile.getEmergencyContact()} is a wire alias
     * that writes into {@code contacts}, so a client sending the old name through the new endpoint
     * produces a {@code contacts} row and no legacy key at all — but a database restored from a
     * backup taken part-way through a migration can hold both, and the newer field has to win.
     *
     * <h2>⛔ The second assertion was the exact opposite of this until F-D</h2>
     *
     * <p>It read {@code .as("and the stale key is still left in place to be looked at").isNotNull()}
     * — <b>a defect pinned as a feature</b>, and the reason the finding survived review. The query
     * was <em>legacy exists AND contacts NOT exists</em>, so this row matched on no run, ever: the
     * {@code unset} could not reach it, and <i>"left in place to be looked at"</i> dressed that up as
     * a decision. Nothing was ever going to look at it.
     *
     * <p>The two effects are separated now — match the legacy key alone, {@code unset} always,
     * {@code set} only when {@code contacts} is absent — which is
     * {@code ProfessionalApplicationConsentMigration.rename}'s shape, the class that made this same
     * mistake first, fixed it, and left a comment naming this migration as the one it had copied.
     *
     * <p>⚠ <b>Both halves asserted in one case, deliberately.</b> Either alone is satisfiable by a
     * wrong implementation: dropping the exists-false clause entirely passes the unset and overwrites
     * {@code Current} with {@code Stale}, and the old {@code AND} query passes the value check and
     * leaves the key. Only the pair pins the shape.
     */
    @Test
    void aRowCarryingBothNamesKeepsTheNewerValueAndLosesTheStaleKey() {
        mongoTemplate.insert(
            new Document("account_id", "legacy-6")
                .append("contacts", List.of(new Document("name", "Current")))
                .append("emergency_contact", new Document("name", "Stale")),
            COLLECTION
        );

        migration.run(null);

        @SuppressWarnings("unchecked")
        List<Document> contacts = (List<Document>) raw("legacy-6").get("contacts");
        assertThat(contacts).as("the newer field wins and is not replaced by the legacy copy").hasSize(1);
        assertThat(contacts.get(0).getString("name")).as("the newer field wins").isEqualTo("Current");
        assertThat(raw("legacy-6").containsKey("emergency_contact"))
            .as("and the stale key is gone — an AND query would have left it here for ever (F-D)")
            .isFalse();
    }

    /**
     * ⚠ And a second run over the same row still changes nothing, which is the property the
     * separation could plausibly have broken.
     *
     * <p>Idempotence here is not "the query stops matching because the target exists" — it never did
     * mean that — but "the key it matches on is the key it removes". Asserted on the both-names row
     * specifically, since that is the one whose match condition F-D widened.
     */
    @Test
    void aSecondRunOverARowThatCarriedBothNamesChangesNothing() {
        mongoTemplate.insert(
            new Document("account_id", "legacy-6b")
                .append("contacts", List.of(new Document("name", "Current")))
                .append("emergency_contact", new Document("name", "Stale")),
            COLLECTION
        );

        migration.run(null);
        Document afterFirst = raw("legacy-6b");
        migration.run(null);

        assertThat(raw("legacy-6b")).isEqualTo(afterFirst);
    }

    /** A profile that never had a next of kin is not given an empty list. */
    @Test
    void aProfileWithNoNextOfKinIsUntouched() {
        mongoTemplate.insert(new Document("account_id", "legacy-7").append("first_name", "Ama"), COLLECTION);

        migration.run(null);

        Document after = raw("legacy-7");
        assertThat(after.containsKey("contacts")).as("absent and empty are different states").isFalse();
        assertThat(after.getString("first_name")).isEqualTo("Ama");
    }

    /** An empty collection is a no-op rather than a failure. */
    @Test
    void anEmptyCollectionIsANoOp() {
        migration.run(null);

        assertThat(profileRepository.count()).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // The assertion that matters: the result is readable through the entity
    // ---------------------------------------------------------------------------------------------

    /**
     * ⛔ <b>The migrated row reads back through {@link Profile}, and this is the case the other tests
     * cannot replace.</b>
     *
     * <p>Every assertion above is about BSON, and BSON can be correct in a shape Spring Data still
     * cannot map — in which case the symptom is not a failed migration but <b>every read of that
     * clinician's profile throwing</b>, long after the run that was reported as successful. So the
     * verdict has to be taken through the entity: the list binds, the nested {@code Address} binds,
     * and the wire alias still projects the first element for the clients that have not migrated.
     */
    @Test
    void theMigratedRowReadsBackThroughTheEntity() {
        insertLegacy(
            "legacy-8",
            new Document("name", "Efua Mensah")
                .append("relationship", "sister")
                .append("phone", "+233200000000")
                .append("address", "12 Oxford St")
        );

        migration.run(null);

        Profile reloaded = profileRepository.findByAccountId("legacy-8").orElseThrow();
        List<EmergencyContact> contacts = reloaded.getContacts();
        assertThat(contacts).hasSize(1);
        assertThat(contacts.get(0).getName()).isEqualTo("Efua Mensah");
        assertThat(contacts.get(0).getRelationship()).isEqualTo("sister");
        assertThat(contacts.get(0).getAddress()).isNotNull();
        assertThat(contacts.get(0).getAddress().getStreetAddress()).isEqualTo("12 Oxford St");
        assertThat(reloaded.getEmergencyContact())
            .as("the wire alias still projects the first element, for web/ until T6 and mobile/ indefinitely")
            .isNotNull();
        assertThat(reloaded.getEmergencyContact().getName()).isEqualTo("Efua Mensah");
    }
}
