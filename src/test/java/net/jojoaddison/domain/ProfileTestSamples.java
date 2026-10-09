package net.jojoaddison.domain;

import java.util.UUID;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.Sex;

public class ProfileTestSamples {

    public static Profile getProfileSample1() {
        return new Profile()
            .id("id1")
            .firstName("firstName1")
            .middleNames("middleNames1")
            .lastName("lastName1")
            .sex(Sex.FEMALE)
            .mobilePhone("mobilePhone1")
            .phoneNumber("phoneNumber1")
            .email("email1")
            .cardType(DocumentType.PASSPORT)
            .cardNumber("cardNumber1")
            .address(AddressTestSamples.getAddressRandomSampleGenerator());
    }

    public static Profile getProfileSample2() {
        return new Profile()
            .id("id2")
            .firstName("firstName2")
            .middleNames("middleNames2")
            .lastName("lastName2")
            .sex(Sex.MALE)
            .mobilePhone("mobilePhone2")
            .phoneNumber("phoneNumber2")
            .email("email2")
            .cardType(DocumentType.CERTIFICATE)
            .cardNumber("cardNumber2")
            .address(AddressTestSamples.getAddressRandomSampleGenerator());
    }

    public static Profile getProfileRandomSampleGenerator() {
        return new Profile()
            .id(UUID.randomUUID().toString())
            .firstName(UUID.randomUUID().toString())
            .middleNames(UUID.randomUUID().toString())
            .lastName(UUID.randomUUID().toString())
            // Fixed, not randomised: `sex` and `cardType` are enums since F9 and a two- and
            // nine-member set has no unique value to mint. Every other field here is random
            // precisely so a sample cannot collide with another, which these two now cannot help
            // with — the id above is what keeps a generated sample distinct.
            .sex(Sex.FEMALE)
            .mobilePhone(UUID.randomUUID().toString())
            .phoneNumber(UUID.randomUUID().toString())
            .email(UUID.randomUUID().toString())
            .cardType(DocumentType.PASSPORT)
            .cardNumber(UUID.randomUUID().toString())
            .address(AddressTestSamples.getAddressRandomSampleGenerator());
    }
}
