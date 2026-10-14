package net.jojoaddison.service.dto;

/**
 * A Data Transfer Object for the Address entity.
 * AddressDTO
 */
public class AddressDTO {

    private String id;
    private String digitalAddress;
    private String streetAddress;
    private String town;
    private String city;
    private String region;
    private String district;
    private String country;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDigitalAddress() {
        return digitalAddress;
    }

    public void setDigitalAddress(String digitalAddress) {
        this.digitalAddress = digitalAddress;
    }

    public String getStreetAddress() {
        return streetAddress;
    }

    public void setStreetAddress(String streetAddress) {
        this.streetAddress = streetAddress;
    }

    public String getTown() {
        return town;
    }

    public void setTown(String town) {
        this.town = town;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    @Override
    public String toString() {
        return (
            "AddressDTO{" +
            "id='" +
            id +
            '\'' +
            ", digitalAddress='" +
            digitalAddress +
            '\'' +
            ", streetAddress='" +
            streetAddress +
            '\'' +
            ", town='" +
            town +
            '\'' +
            ", city='" +
            city +
            '\'' +
            ", region='" +
            region +
            '\'' +
            ", district='" +
            district +
            '\'' +
            ", country='" +
            country +
            '\'' +
            '}'
        );
    }
}
