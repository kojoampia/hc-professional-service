package net.jojoaddison.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Embedded emergency contact / next of kin on a professional {@link Profile}
 * (onboarding workflow § Data contracts — deliberately not a Profile
 * self-reference).
 */
public class EmergencyContact implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;

    private String relationship;

    private String phone;

    private String email;

    private String address;

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

    public String getAddress() {
        return address;
    }

    public EmergencyContact address(String address) {
        this.address = address;
        return this;
    }

    public void setAddress(String address) {
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
