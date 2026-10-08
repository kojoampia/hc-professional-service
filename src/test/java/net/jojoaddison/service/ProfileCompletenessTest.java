package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.jojoaddison.domain.Address;
import net.jojoaddison.domain.EmergencyContact;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.domain.enumeration.Sex;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The 39 values {@code profile.md} step 2 requires (F1).
 *
 * <p>{@code profile.md}: <i>"Every field in the Profile model is required"</i>, and under the
 * endpoint <i>"Set {@code profile.status} to {@code PROFILE_COMPLETED} when every field is
 * provided"</i>. The owner's reading is the literal, nested one — 10 {@code Profile} fields, 7 on
 * {@code Profile.address}, and 11 on <b>each</b> of at least two contacts.
 *
 * <h2>⭐ Withhold one value at a time, generated rather than written out</h2>
 *
 * <p>{@link #everyRequiredValueIsRequired()} is a {@link TestFactory} over a list of mutators, each
 * of which removes exactly one value from an otherwise complete profile. That shape is the point and
 * not a convenience: <b>a hand-written case per field is a list maintained by hand, and the failure
 * mode of this predicate is a field somebody forgot</b> — which is how the thing it replaces came to
 * check 14 values while the specification named 39. A reader counts the generated cases and compares
 * the number with the specification, which is a check a prose list cannot offer.
 *
 * <p>⚠ The count is asserted <b>as a number</b> in {@link #thereAreThirtyNineRequiredValues()} as
 * well, because a mutator accidentally deleted from the list below would otherwise just quietly stop
 * being tested.
 */
class ProfileCompletenessTest {

    // -------------------------------------------------------------------------------------------------
    // A complete profile, and the single-value removals
    // -------------------------------------------------------------------------------------------------

    private static Address completeAddress() {
        return new Address()
            .digitalAddress("GA-123-4567")
            .streetAddress("12 Oxford St")
            .town("Osu")
            .city("Accra")
            .district("Ayawaso East")
            .region("Greater Accra")
            .country("Ghana");
    }

    private static EmergencyContact completeContact(String name) {
        return new EmergencyContact()
            .name(name)
            .relationship("sister")
            .email(name.toLowerCase(java.util.Locale.ROOT) + "@example.com")
            .phone("+233200000001")
            .address(completeAddress());
    }

    private static Profile completeProfile() {
        return new Profile()
            .firstName("Ama")
            .middleNames("Nana Yaa")
            .lastName("Boateng")
            .birthDate(LocalDate.of(1990, 1, 1))
            .sex(Sex.FEMALE)
            .mobilePhone("+233200000000")
            .phoneNumber("+233300000000")
            .email("ama@example.com")
            .cardType(DocumentType.GHANACARD)
            .cardNumber("GHA-1")
            .address(completeAddress())
            .contacts(new ArrayList<>(List.of(completeContact("Efua"), completeContact("Kojo"))));
    }

    /** One removal: what it is called, and what it takes away. */
    private record Removal(String value, Consumer<Profile> remove) {}

    private static List<Removal> removals() {
        List<Removal> removals = new ArrayList<>();

        // --- The ten Profile fields -----------------------------------------------------------------
        removals.add(new Removal("firstName", p -> p.setFirstName(null)));
        removals.add(new Removal("middleNames", p -> p.setMiddleNames(null)));
        removals.add(new Removal("lastName", p -> p.setLastName(null)));
        removals.add(new Removal("birthDate", p -> p.setBirthDate(null)));
        removals.add(new Removal("sex", p -> p.setSex(null)));
        removals.add(new Removal("mobilePhone", p -> p.setMobilePhone(null)));
        removals.add(new Removal("phoneNumber", p -> p.setPhoneNumber(null)));
        removals.add(new Removal("email", p -> p.setEmail(null)));
        removals.add(new Removal("cardType", p -> p.setCardType(null)));
        removals.add(new Removal("cardNumber", p -> p.setCardNumber(null)));

        // --- The seven Profile.address fields -------------------------------------------------------
        addressRemovals()
            .forEach(removal -> removals.add(new Removal("address." + removal.value(), p -> removal.remove().accept(p.getAddress()))));

        // --- Eleven on each of the two contacts -----------------------------------------------------
        for (int index = 0; index < ProfileCompleteness.REQUIRED_CONTACTS; index++) {
            int at = index;
            removals.add(new Removal("contacts[" + at + "].name", p -> p.getContacts().get(at).setName(null)));
            removals.add(new Removal("contacts[" + at + "].relationship", p -> p.getContacts().get(at).setRelationship(null)));
            removals.add(new Removal("contacts[" + at + "].email", p -> p.getContacts().get(at).setEmail(null)));
            removals.add(new Removal("contacts[" + at + "].phone", p -> p.getContacts().get(at).setPhone(null)));
            addressRemovals()
                .forEach(
                    removal ->
                        removals.add(
                            new Removal(
                                "contacts[" + at + "].address." + removal.value(),
                                p -> removal.remove().accept(p.getContacts().get(at).getAddress())
                            )
                        )
                );
        }

        return removals;
    }

    /** The seven address values, as removals against an {@link Address} rather than a {@link Profile}. */
    private record AddressRemoval(String value, Consumer<Address> remove) {}

    private static List<AddressRemoval> addressRemovals() {
        return List.of(
            new AddressRemoval("digitalAddress", a -> a.setDigitalAddress(null)),
            new AddressRemoval("streetAddress", a -> a.setStreetAddress(null)),
            new AddressRemoval("town", a -> a.setTown(null)),
            new AddressRemoval("city", a -> a.setCity(null)),
            new AddressRemoval("district", a -> a.setDistrict(null)),
            new AddressRemoval("region", a -> a.setRegion(null)),
            new AddressRemoval("country", a -> a.setCountry(null))
        );
    }

    // -------------------------------------------------------------------------------------------------
    // The cases
    // -------------------------------------------------------------------------------------------------

    @Test
    void aProfileProvidingEveryRequiredValueIsComplete() {
        assertThat(ProfileCompleteness.isComplete(completeProfile())).isTrue();
    }

    /**
     * <b>The count, as a number, so a deleted mutator cannot pass as silence.</b> 10 + 7 + 2 × 11 =
     * 39, which is the figure {@code profile.md}'s step 2 comes to on the owner's reading.
     */
    @Test
    void thereAreThirtyNineRequiredValues() {
        assertThat(removals())
            .as("10 Profile fields + 7 on Profile.address + 11 on each of %d contacts", ProfileCompleteness.REQUIRED_CONTACTS)
            .hasSize(39);
        assertThat(removals().stream().map(Removal::value)).doesNotHaveDuplicates();
    }

    @TestFactory
    Iterable<DynamicTest> everyRequiredValueIsRequired() {
        return removals()
            .stream()
            .map(removal ->
                DynamicTest.dynamicTest("a profile missing " + removal.value() + " is not complete", () -> {
                    Profile profile = completeProfile();
                    removal.remove().accept(profile);
                    assertThat(ProfileCompleteness.isComplete(profile)).isFalse();
                }))
            .toList();
    }

    /**
     * ⛔ <b>Blank is not provided.</b> A string of spaces is what an untouched input posts, and
     * counting it would make the whole predicate satisfiable by submitting an empty form.
     */
    @Test
    void aBlankStringIsNotProvided() {
        Profile profile = completeProfile();
        profile.setFirstName("   ");

        assertThat(ProfileCompleteness.isComplete(profile)).isFalse();
    }

    // -------------------------------------------------------------------------------------------------
    // The contacts rule (F2), which is the one place this predicate is about a COUNT
    // -------------------------------------------------------------------------------------------------

    /** {@code profile.md}: <i>"At least two emergency contacts are required."</i> */
    @Test
    void oneCompleteContactIsNotEnough() {
        Profile profile = completeProfile();
        profile.setContacts(new ArrayList<>(List.of(completeContact("Efua"))));

        assertThat(ProfileCompleteness.isComplete(profile)).isFalse();
    }

    @Test
    void noContactsAtAllIsNotEnough() {
        Profile profile = completeProfile();
        profile.setContacts(null);
        assertThat(ProfileCompleteness.isComplete(profile)).isFalse();

        profile.setContacts(new ArrayList<>());
        assertThat(ProfileCompleteness.isComplete(profile)).isFalse();
    }

    /** More than two is fine; "at least" is not "exactly". */
    @Test
    void threeCompleteContactsAreStillComplete() {
        Profile profile = completeProfile();
        profile.setContacts(new ArrayList<>(List.of(completeContact("Efua"), completeContact("Kojo"), completeContact("Adwoa"))));

        assertThat(ProfileCompleteness.isComplete(profile)).isTrue();
    }

    /**
     * ⚠ <b>EVERY contact must be complete, not merely two of them</b> — and this is where the
     * predicate deliberately differs from {@code OnboardingService.nextOfKinComplete}, which counts
     * the complete ones.
     *
     * <p>The reason for the difference: a progress meter that goes <em>down</em> when a clinician
     * starts typing a third contact punishes the behaviour the form invites, whereas a
     * {@code status} saying "complete" over a half-typed contact would simply be false. Two
     * questions, two answers, and {@code ProfileCompleteness}'s javadoc says which is authoritative
     * for what.
     */
    @Test
    void aThirdHalfTypedContactMakesTheProfileIncomplete() {
        Profile profile = completeProfile();
        profile.setContacts(
            new ArrayList<>(List.of(completeContact("Efua"), completeContact("Kojo"), new EmergencyContact().name("Adwoa")))
        );

        assertThat(ProfileCompleteness.isComplete(profile)).isFalse();
    }

    // -------------------------------------------------------------------------------------------------
    // What is deliberately NOT required
    // -------------------------------------------------------------------------------------------------

    /**
     * ⛔ <b>{@code title} is not in {@code profile.md}'s Profile model table</b>, so it is not one of
     * the 39 — even though the page header renders {@code profile.title}. This case is what fails if
     * somebody "finishes" the predicate by requiring every field {@code Profile} happens to declare.
     */
    @Test
    void titleIsNotRequired() {
        Profile profile = completeProfile();
        assertThat(profile.getTitle()).as("the fixture never sets it").isNull();

        assertThat(ProfileCompleteness.isComplete(profile)).isTrue();
    }

    /**
     * Neither is {@code status} itself — requiring it would be circular, since this predicate is what
     * decides it.
     */
    @Test
    void statusIsNotRequired() {
        Profile profile = completeProfile();
        assertThat(profile.getStatus()).isNull();
        assertThat(ProfileCompleteness.isComplete(profile)).isTrue();

        profile.setStatus(ProfileStatus.PROFILE_COMPLETED);
        assertThat(ProfileCompleteness.isComplete(profile)).isTrue();
    }

    /** Nor the identifiers: {@code id} is minted and {@code accountId} comes from the token. */
    @Test
    void neitherIdentifierIsRequired() {
        Profile profile = completeProfile();
        assertThat(profile.getId()).isNull();
        assertThat(profile.getAccountId()).isNull();

        assertThat(ProfileCompleteness.isComplete(profile)).isTrue();
    }

    /**
     * Nor {@code Address.id}, which is the embedded address's {@code @Id} and is never set on one —
     * the specification's own note calls it "not a user input".
     */
    @Test
    void theEmbeddedAddressIdIsNotRequired() {
        Profile profile = completeProfile();
        assertThat(profile.getAddress().getId()).isNull();
        assertThat(profile.getContacts().get(0).getAddress().getId()).isNull();

        assertThat(ProfileCompleteness.isComplete(profile)).isTrue();
    }

    @Test
    void aNullProfileIsNotComplete() {
        assertThat(ProfileCompleteness.isComplete(null)).isFalse();
    }
}
