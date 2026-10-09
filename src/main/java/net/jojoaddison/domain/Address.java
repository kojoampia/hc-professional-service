package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A Address.
 *
 * <h2>Eight fields, because {@code profile.md}'s Address model has eight (F10)</h2>
 *
 * <p>This class carried {@code areaCode} and {@code state} as well, and the specification's model
 * names neither. They were <b>input</b> fields — writable by {@code PUT /api/profile} and by the
 * admin {@code PATCH} — that no form in the estate has ever filled and nothing would ever populate,
 * and since {@code EmergencyContact.address} became an embedded {@code Address} they were multiplied
 * by the length of {@link Profile#getContacts()}. Removed with
 * {@code AddressFieldRemovalMigration}, which drops the stored keys.
 *
 * <p><b>Nothing was discarded.</b> Measured on the quality stack before the deletion: <b>0</b>
 * documents carried a value in either field — not in {@code profile.address}, not in
 * {@code profile.contacts[].address}, and the standalone {@code address} collection was empty. The
 * migration exists for production and for any database restored from an older backup, not because
 * the quality box had anything to move.
 */
@Document(collection = "address")
@SuppressWarnings("common-java:DuplicatedBlocks")
public class Address implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @Field("digital_address")
    private String digitalAddress;

    @Field("street_address")
    private String streetAddress;

    @Field("town")
    private String town;

    @Field("city")
    private String city;

    @Field("district")
    private String district;

    @Field("region")
    private String region;

    @Field("country")
    private String country;

    @Field("created_date")
    private LocalDate createdDate;

    @Field("modified_date")
    private LocalDate modifiedDate;

    @Field("created_by")
    private String createdBy;

    @Field("modified_by")
    private String modifiedBy;

    // jhipster-needle-entity-add-field - JHipster will add fields here

    public String getId() {
        return this.id;
    }

    public Address id(String id) {
        this.setId(id);
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDigitalAddress() {
        return this.digitalAddress;
    }

    public Address digitalAddress(String digitalAddress) {
        this.setDigitalAddress(digitalAddress);
        return this;
    }

    public void setDigitalAddress(String digitalAddress) {
        this.digitalAddress = digitalAddress;
    }

    public String getStreetAddress() {
        return this.streetAddress;
    }

    public Address streetAddress(String streetAddress) {
        this.setStreetAddress(streetAddress);
        return this;
    }

    public void setStreetAddress(String streetAddress) {
        this.streetAddress = streetAddress;
    }

    public String getTown() {
        return this.town;
    }

    public Address town(String town) {
        this.setTown(town);
        return this;
    }

    public void setTown(String town) {
        this.town = town;
    }

    public String getCity() {
        return this.city;
    }

    public Address city(String city) {
        this.setCity(city);
        return this;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getDistrict() {
        return this.district;
    }

    public Address district(String district) {
        this.setDistrict(district);
        return this;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getRegion() {
        return this.region;
    }

    public Address region(String region) {
        this.setRegion(region);
        return this;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getCountry() {
        return this.country;
    }

    public Address country(String country) {
        this.setCountry(country);
        return this;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public LocalDate getCreatedDate() {
        return this.createdDate;
    }

    public Address createdDate(LocalDate createdDate) {
        this.setCreatedDate(createdDate);
        return this;
    }

    public void setCreatedDate(LocalDate createdDate) {
        this.createdDate = createdDate;
    }

    public LocalDate getModifiedDate() {
        return this.modifiedDate;
    }

    public Address modifiedDate(LocalDate modifiedDate) {
        this.setModifiedDate(modifiedDate);
        return this;
    }

    public void setModifiedDate(LocalDate modifiedDate) {
        this.modifiedDate = modifiedDate;
    }

    public String getCreatedBy() {
        return this.createdBy;
    }

    public Address createdBy(String createdBy) {
        this.setCreatedBy(createdBy);
        return this;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getModifiedBy() {
        return this.modifiedBy;
    }

    public Address modifiedBy(String modifiedBy) {
        this.setModifiedBy(modifiedBy);
        return this;
    }

    public void setModifiedBy(String modifiedBy) {
        this.modifiedBy = modifiedBy;
    }

    // jhipster-needle-entity-add-getters-setters - JHipster will add getters and setters here

    /**
     * The eight fields {@code profile.md}'s Address model names, in its order — for a holder that
     * <b>embeds</b> this address and therefore cannot compare it by identity.
     *
     * <h2>Why this exists at all, which is a real defect and not a modelling preference</h2>
     *
     * <p>{@link #equals(Object)} below is identity-based, the JHipster convention for a
     * {@code @Document}: it answers {@code false} whenever {@code id} is null, <b>including for two
     * addresses that are field-for-field identical</b>. That is correct for a row in the
     * {@code address} collection and wrong for an embedded one, which has no id and never gets one —
     * {@code Profile.address} stores null there and so does every {@code EmergencyContact.address}.
     * The visible consequence was that two identical next-of-kin never compared equal, so
     * {@code List<EmergencyContact>} did not behave: {@code contains}, {@code indexOf} and
     * {@code equals} all answered on a property nobody had set. {@code EmergencyContact} uses this
     * method rather than {@code equals}.
     *
     * <p>⛔ <b>{@code equals} is deliberately NOT changed to value semantics.</b> {@code AddressTest}
     * asserts the generated contract — two addresses sharing an id are equal whatever else differs —
     * and that contract is the right one for the collection this class is a document of. The fix
     * belongs where the breakage is: in the holder that embeds it.
     *
     * <p><b>The four audit fields are excluded on purpose.</b> {@code createdDate},
     * {@code modifiedDate}, {@code createdBy} and {@code modifiedBy} are metadata about a write, not
     * part of what the address <em>is</em>, and nothing stamps them on an embedded one. Including
     * them would make two identical addresses stop being equal because one of them had been touched.
     *
     * <p>{@link Arrays#asList} and not {@code List.of}, which rejects nulls — every field here is
     * null on an address somebody filled in partially, which is most of them.
     */
    public List<Object> values() {
        return Arrays.asList(id, digitalAddress, streetAddress, town, city, district, region, country);
    }

    /** Whether {@code other} carries the same eight {@link #values()} — see that method. */
    public boolean hasSameValuesAs(Address other) {
        return other != null && values().equals(other.values());
    }

    /** A hash over {@link #values()}, so a holder's {@code hashCode} can agree with its own equality. */
    public int valuesHashCode() {
        return Objects.hash(values().toArray());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Address)) {
            return false;
        }
        return getId() != null && getId().equals(((Address) o).getId());
    }

    @Override
    public int hashCode() {
        // see https://vladmihalcea.com/how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier/
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "Address{" +
            "id=" + getId() +
            ", digitalAddress='" + getDigitalAddress() + "'" +
            ", streetAddress='" + getStreetAddress() + "'" +
            ", town='" + getTown() + "'" +
            ", city='" + getCity() + "'" +
            ", district='" + getDistrict() + "'" +
            ", region='" + getRegion() + "'" +
            ", country='" + getCountry() + "'" +
            ", createdDate='" + getCreatedDate() + "'" +
            ", modifiedDate='" + getModifiedDate() + "'" +
            ", createdBy='" + getCreatedBy() + "'" +
            ", modifiedBy='" + getModifiedBy() + "'" +
            "}";
    }
}
