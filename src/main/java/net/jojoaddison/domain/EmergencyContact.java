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
 * <h2>{@code id} is a field of this model, because {@code profile.md} gives it one (F5)</h2>
 *
 * <p>It was omitted, and the reasoning recorded here for the omission — <i>a value type needs no
 * id</i> — <b>was overruled by the owner</b>. {@code profile.md}'s EmergencyContact model lists
 * {@code id} first and that document is authoritative; "the existing model is more defensible" is
 * not an argument against building what was specified. It is <b>not</b> a Mongo {@code @Id}: this
 * class has no {@code @Document} and no repository, so the field is an ordinary member of the
 * embedded object, carried on the wire and stored inside {@code profile.contacts}. Nothing in this
 * service mints one and no form collects one, which is the specification's own note about it
 * ("not a user input"); a client that supplies one has it stored and returned, which is what lets a
 * form address the right element of the list when there is more than one.
 *
 * <h2>⚠ {@code equals} compares the address <em>by value</em>, and that was a live bug</h2>
 *
 * <p>{@link Address#equals(Object)} is identity-based — the JHipster convention for a
 * {@code @Document} — so it answers {@code false} whenever {@code id} is null, which is the shape
 * <em>every</em> embedded address has. This class's {@code equals} delegated to it, so <b>two
 * field-for-field identical contacts never compared equal</b> and {@code List<EmergencyContact>}
 * did not behave: {@code contains}, {@code indexOf} and list {@code equals} all answered on a
 * property nobody sets. Fixed through {@link Address#hasSameValuesAs(Address)} and
 * {@link Address#valuesHashCode()} — the fix is here rather than on {@code Address}, whose
 * identity contract is right for the collection it is a document of. {@code EmergencyContactTest}
 * holds it.
 */
public class EmergencyContact implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String name;

    private String relationship;

    private String phone;

    private String email;

    private Address address;

    public String getId() {
        return id;
    }

    public EmergencyContact id(String id) {
        this.id = id;
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

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
            Objects.equals(id, other.id) &&
            Objects.equals(name, other.name) &&
            Objects.equals(relationship, other.relationship) &&
            Objects.equals(phone, other.phone) &&
            Objects.equals(email, other.email) &&
            sameAddress(other.address)
        );
    }

    /**
     * Address equality by value, never by identity — see the class note. {@code null == null} is the
     * ordinary case: a contact with no address is as valid as one with.
     */
    private boolean sameAddress(Address other) {
        return address == null ? other == null : address.hasSameValuesAs(other);
    }

    @Override
    public int hashCode() {
        // Agrees with equals above: the address contributes its VALUE hash, because
        // Address.hashCode() is getClass().hashCode() and so says nothing about two addresses being
        // the same one. Were this Objects.hash(..., address), two equal contacts would hash alike by
        // accident rather than by construction, and a contact whose address changed would not.
        return Objects.hash(id, name, relationship, phone, email, address == null ? 0 : address.valuesHashCode());
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "EmergencyContact{" +
                "id='" + getId() + "'" +
                ", name='" + getName() + "'" +
                ", relationship='" + getRelationship() + "'" +
                ", phone='" + getPhone() + "'" +
                ", email='" + getEmail() + "'" +
                ", address='" + getAddress() + "'" +
                "}";
    }
}
