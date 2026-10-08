package net.jojoaddison.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Embedded emergency contact / next of kin on a professional {@link Profile}
 * (onboarding workflow § Data contracts — deliberately not a Profile
 * self-reference).
 *
 * <h2>{@code address} is a structured {@link Address}, not a line of text (profile.md, T1)</h2>
 *
 * <p>It was a {@code String} until this change. {@code profile.md}'s EmergencyContact model types it
 * {@code Address}, and that document is authoritative — but the storage consequence is worth stating
 * where somebody will read it, because it is not what the one-word type change looks like.
 * {@link Address} is a {@code @Document(collection = "address")} carrying its own {@code @Id} and
 * four audit fields, so <b>every next-of-kin now stores an id and four audit fields nothing fills</b>,
 * multiplied by the length of {@link Profile#getContacts()}. {@code Profile.address} has always had
 * that shape, so this is the existing cost taken a second and third time rather than a new kind of
 * cost — and the alternative, a second flattened address type for this one use, would be a second
 * answer to "what is an address here" for the sake of four nulls.
 *
 * <p><b>{@code EmergencyContactListMigration} is what moves the old value</b>: the stored line of
 * text becomes {@code address.streetAddress}, which is the field the wizard's street line already
 * writes and the one {@code OnboardingService.addressComplete} reads. A blank or absent line leaves
 * no {@code Address} at all rather than an empty one, because an address nobody entered and an
 * address with every field empty should not read the same.
 *
 * <p><b>No {@code id} of its own, and {@code profile.md} gives it one.</b> That is a deliberate
 * divergence recorded rather than implemented: this is a value type with no {@code @Document}, no
 * repository and no endpoint, so an id would be a field no writer sets, no reader joins on and no
 * form may collect — the specification's own note calls it "not a user input". If a contact ever
 * needs addressing individually it comes back as a decision, with whatever sets it.
 *
 * <p><b>{@code equals} inherits {@link Address}'s identity semantics</b>, which are id-based and
 * answer false for two addresses whose ids are null — the shape every embedded one has. Nothing in
 * the service compares contacts, and the write paths replace wholesale rather than diffing, so this
 * is recorded rather than worked around.
 */
public class EmergencyContact implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;

    private String relationship;

    private String phone;

    private String email;

    private Address address;

    public String getName() {
        return name;
    }

    public EmergencyContact name(String name) {
        this.name = name;
        return this;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRelationship() {
        return relationship;
    }

    public EmergencyContact relationship(String relationship) {
        this.relationship = relationship;
        return this;
    }

    public void setRelationship(String relationship) {
        this.relationship = relationship;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public EmergencyContact email(String email) {
        this.email = email;
        return this;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Address getAddress() {
        return address;
    }

    public EmergencyContact address(Address address) {
        this.address = address;
        return this;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public EmergencyContact phone(String phone) {
        this.phone = phone;
        return this;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EmergencyContact)) {
            return false;
        }
        EmergencyContact other = (EmergencyContact) o;
        return (
            Objects.equals(name, other.name) &&
            Objects.equals(relationship, other.relationship) &&
            Objects.equals(phone, other.phone) &&
            Objects.equals(email, other.email) &&
            Objects.equals(address, other.address)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, relationship, phone, email, address);
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "EmergencyContact{" +
                "name='" + getName() + "'" +
                ", relationship='" + getRelationship() + "'" +
                ", phone='" + getPhone() + "'" +
                ", email='" + getEmail() + "'" +
                ", address='" + getAddress() + "'" +
                "}";
    }
}
