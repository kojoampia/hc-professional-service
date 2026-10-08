package net.jojoaddison.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link EmergencyContact}'s {@code id} (F5) and its equality, which was broken.
 *
 * <h2>⚠ The defect: two identical contacts never compared equal</h2>
 *
 * <p>{@code EmergencyContact.equals} compared its embedded {@link Address} with
 * {@code Objects.equals}, and {@code Address.equals} is <b>identity-based</b> — the JHipster
 * convention for a {@code @Document} — so it answers {@code false} whenever {@code id} is null,
 * which is the shape <em>every</em> embedded address has. Nothing sets an id on one, so two
 * field-for-field identical contacts were never equal and {@code List<EmergencyContact>} did not
 * behave: {@code contains}, {@code indexOf} and list {@code equals} all answered on a property
 * nobody had set.
 *
 * <p>Fixed by comparing the address through {@link Address#hasSameValuesAs(Address)} rather than by
 * changing {@code Address.equals}, whose identity contract is right for the collection it is a
 * document of — {@code AddressTest} holds that contract and stays green.
 */
class EmergencyContactTest {

    private static Address address(String street, String city) {
        return new Address().streetAddress(street).city(city).region("Greater Accra").country("Ghana");
    }

    private static EmergencyContact contact(String street) {
        return new EmergencyContact()
            .name("Efua Mensah")
            .relationship("sister")
            .email("efua@example.com")
            .phone("+233200000001")
            .address(address(street, "Accra"));
    }

    // -------------------------------------------------------------------------------------------------
    // id — profile.md's EmergencyContact model gives it one (F5)
    // -------------------------------------------------------------------------------------------------

    /**
     * It is an ordinary member, carried and stored like the other four.
     *
     * <p>Not a Mongo {@code @Id}: this class has no {@code @Document} and no repository, so the field
     * lives inside {@code profile.contacts}. Nothing in the service mints one — the specification's
     * own note calls it "not a user input" — and a client that supplies one has it kept, which is
     * what lets a form address the right element of a list with more than one entry.
     */
    @Test
    void theIdIsReadableAndWritable() {
        EmergencyContact contact = contact("12 Oxford St").id("contact-1");

        assertThat(contact.getId()).isEqualTo("contact-1");

        contact.setId("contact-2");
        assertThat(contact.getId()).isEqualTo("contact-2");
    }

    /** It takes part in equality, as the other five values do. */
    @Test
    void twoContactsDifferingOnlyByIdAreNotEqual() {
        assertThat(contact("12 Oxford St").id("contact-1")).isNotEqualTo(contact("12 Oxford St").id("contact-2"));
    }

    /** And its absence is the ordinary case, since nothing mints one. */
    @Test
    void twoContactsWithNoIdAtAllAreStillEqual() {
        assertThat(contact("12 Oxford St")).isEqualTo(contact("12 Oxford St"));
    }

    // -------------------------------------------------------------------------------------------------
    // equals / hashCode — the live bug (F5)
    // -------------------------------------------------------------------------------------------------

    /**
     * ⛔ <b>The case that was red before the fix.</b> Two identical contacts, each with an embedded
     * address carrying no id — which is every embedded address there has ever been.
     */
    @Test
    void twoIdenticalContactsWithEmbeddedAddressesAreEqual() {
        EmergencyContact one = contact("12 Oxford St");
        EmergencyContact two = contact("12 Oxford St");

        assertThat(one.getAddress().getId()).as("an embedded address never has one, which is the whole defect").isNull();
        assertThat(one).isEqualTo(two);
        assertThat(one).hasSameHashCodeAs(two);
    }

    /** The control: a genuine difference inside the address is still a difference. */
    @Test
    void contactsWhoseAddressesDifferAreNotEqual() {
        assertThat(contact("12 Oxford St")).isNotEqualTo(contact("3 High St"));
    }

    /** {@code hashCode} must agree, or a contact cannot be found in a hash-based collection. */
    @Test
    void equalContactsHashAlike() {
        assertThat(java.util.Set.of(contact("12 Oxford St"))).contains(contact("12 Oxford St"));
    }

    /** A contact with no address at all is equal to another with none — {@code null == null}. */
    @Test
    void twoContactsWithNoAddressAreEqual() {
        EmergencyContact one = new EmergencyContact().name("Efua").relationship("sister");
        EmergencyContact two = new EmergencyContact().name("Efua").relationship("sister");

        assertThat(one).isEqualTo(two).hasSameHashCodeAs(two);
    }

    /** And one with an address is not equal to one without. */
    @Test
    void aContactWithAnAddressIsNotEqualToOneWithout() {
        EmergencyContact withAddress = contact("12 Oxford St");
        EmergencyContact without = contact("12 Oxford St");
        without.setAddress(null);

        assertThat(withAddress).isNotEqualTo(without);
        assertThat(without).isNotEqualTo(withAddress);
    }

    // -------------------------------------------------------------------------------------------------
    // What the fix was actually for: the list
    // -------------------------------------------------------------------------------------------------

    /**
     * ⭐ <b>The reason this matters rather than being a tidiness point.</b> {@code Profile.contacts}
     * is a {@code List}, and a list's own {@code equals}, {@code contains} and {@code indexOf} are
     * defined by its elements'. With the old id-based comparison every one of these answered wrongly
     * on contacts nobody could tell apart by eye.
     */
    @Test
    void theContactsListBehaves() {
        List<EmergencyContact> stored = List.of(contact("12 Oxford St"), contact("3 High St"));

        assertThat(stored).isEqualTo(List.of(contact("12 Oxford St"), contact("3 High St")));
        assertThat(stored).contains(contact("3 High St"));
        assertThat(stored.indexOf(contact("3 High St"))).isEqualTo(1);
        assertThat(stored).doesNotContain(contact("99 Nowhere Rd"));
    }

    /**
     * {@code Address.hasSameValuesAs} ignores the four audit fields on purpose: they are metadata
     * about a write, not part of what an address <em>is</em>, and an embedded one has none set. Were
     * they compared, touching a row would stop two identical addresses being identical.
     */
    @Test
    void anAuditFieldDoesNotMakeTwoAddressesDifferent() {
        EmergencyContact touched = contact("12 Oxford St");
        touched.getAddress().setModifiedBy("someone");

        assertThat(touched).isEqualTo(contact("12 Oxford St"));
    }
}
