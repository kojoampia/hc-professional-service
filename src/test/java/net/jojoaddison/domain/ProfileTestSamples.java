package net.jojoaddison.domain;

import java.util.UUID;

public class ProfileTestSamples {

    public static Profile getProfileSample1() {
        return new Profile()
            .id("id1")
            .firstName("firstName1")
            .middleNames("middleNames1")
            .lastName("lastName1")
            .sex("sex1")
            .mobilePhone("mobilePhone1")
            .phoneNumber("phoneNumber1")
            .email("email1")
            .cardType("cardType1")
            .cardNumber("cardNumber1")
            .address(AddressTestSamples.getAddressRandomSampleGenerator());
    }

    public static Profile getProfileSample2() {
        return new Profile()
            .id("id2")
            .firstName("firstName2")
            .middleNames("middleNames2")
            .lastName("lastName2")
            .sex("sex2")
            .mobilePhone("mobilePhone2")
            .phoneNumber("phoneNumber2")
            .email("email2")
            .cardType("cardType2")
            .cardNumber("cardNumber2")
            .address(AddressTestSamples.getAddressRandomSampleGenerator());
    }

    public static Profile getProfileRandomSampleGenerator() {
        return new Profile()
            .id(UUID.randomUUID().toString())
            .firstName(UUID.randomUUID().toString())
            .middleNames(UUID.randomUUID().toString())
            .lastName(UUID.randomUUID().toString())
            .sex(UUID.randomUUID().toString())
            .mobilePhone(UUID.randomUUID().toString())
            .phoneNumber(UUID.randomUUID().toString())
            .email(UUID.randomUUID().toString())
            .cardType(UUID.randomUUID().toString())
            .cardNumber(UUID.randomUUID().toString())
            .address(AddressTestSamples.getAddressRandomSampleGenerator());
    }
}
