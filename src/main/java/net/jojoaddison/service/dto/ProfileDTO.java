package net.jojoaddison.service.dto;

import java.util.List;

/**
 * A Data Transfer Object for the Profile entity.
 * ProfileDTO
 */
public class ProfileDTO {

    private String id;
    private String title;
    private String firstName;
    private String middleNames;
    private String lastName;
    private String sex;
    private String birthDate;
    private String email;
    private String mobileNumber;
    private String phoneNumber;
    private String cardType;
    private String cardNumber;
    private AddressDTO address;
    private String accountId;
    private List<EmergencyContactDTO> contacts;
    private String status;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getMiddleNames() {
        return middleNames;
    }

    public void setMiddleNames(String middleNames) {
        this.middleNames = middleNames;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getSex() {
        return sex;
    }

    public void setSex(String sex) {
        this.sex = sex;
    }

    public String getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(String birthDate) {
        this.birthDate = birthDate;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getMobileNumber() {
        return mobileNumber;
    }

    public void setMobileNumber(String mobileNumber) {
        this.mobileNumber = mobileNumber;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getCardType() {
        return cardType;
    }

    public void setCardType(String cardType) {
        this.cardType = cardType;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public AddressDTO getAddress() {
        return address;
    }

    public void setAddress(AddressDTO address) {
        this.address = address;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public List<EmergencyContactDTO> getContacts() {
        return contacts;
    }

    public void setContacts(List<EmergencyContactDTO> contacts) {
        this.contacts = contacts;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return (
            "ProfileDTO{" +
            "id='" +
            id +
            '\'' +
            ", title='" +
            title +
            '\'' +
            ", firstName='" +
            firstName +
            '\'' +
            ", middleNames='" +
            middleNames +
            '\'' +
            ", lastName='" +
            lastName +
            '\'' +
            ", sex='" +
            sex +
            '\'' +
            ", birthDate='" +
            birthDate +
            '\'' +
            ", email='" +
            email +
            '\'' +
            ", mobileNumber='" +
            mobileNumber +
            '\'' +
            ", phoneNumber='" +
            phoneNumber +
            '\'' +
            ", cardType='" +
            cardType +
            '\'' +
            ", cardNumber='" +
            cardNumber +
            '\'' +
            ", address=" +
            address +
            ", accountId='" +
            accountId +
            '\'' +
            ", contacts=" +
            contacts +
            ", status='" +
            status +
            '\'' +
            '}'
        );
    }
}
