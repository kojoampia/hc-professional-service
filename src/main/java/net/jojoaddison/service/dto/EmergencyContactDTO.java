package net.jojoaddison.service.dto;

/**
 * A Data Transfer Object for the EmergencyContact entity.
 * EmergencyContactDTO
 */

public class EmergencyContactDTO {

    private String id;
    private String name;
    private String relationship;
    private String phoneNumber;
    private String email;
    private AddressDTO address;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRelationship() {
        return relationship;
    }

    public void setRelationship(String relationship) {
        this.relationship = relationship;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public AddressDTO getAddress() {
        return address;
    }

    public void setAddress(AddressDTO address) {
        this.address = address;
    }

    @Override
    public String toString() {
        return (
            "EmergencyContactDTO{" +
            "id='" +
            id +
            '\'' +
            ", name='" +
            name +
            '\'' +
            ", relationship='" +
            relationship +
            '\'' +
            ", phoneNumber='" +
            phoneNumber +
            '\'' +
            ", email='" +
            email +
            '\'' +
            ", address=" +
            address +
            '}'
        );
    }
}
