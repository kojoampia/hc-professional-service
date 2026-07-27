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
        return Objects.equals(name, other.name) && Objects.equals(relationship, other.relationship) && Objects.equals(phone, other.phone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, relationship, phone);
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "EmergencyContact{" +
                "name='" + getName() + "'" +
                ", relationship='" + getRelationship() + "'" +
                ", phone='" + getPhone() + "'" +
                "}";
    }
}
