package net.jojoaddison.domain;

import java.util.UUID;

public class AddressTestSamples {

    public static Address getAddressSample1() {
        return new Address()
            .id("id1")
            .digitalAddress("digitalAddress1")
            .streetAddress("streetAddress1")
            .areaCode("areaCode1")
            .town("town1")
            .city("city1")
            .district("district1")
            .state("state1")
            .region("region1")
            .country("country1")
            .createdBy("createdBy1")
            .modifiedBy("modifiedBy1");
    }

    public static Address getAddressSample2() {
        return new Address()
            .id("id2")
            .digitalAddress("digitalAddress2")
            .streetAddress("streetAddress2")
            .areaCode("areaCode2")
            .town("town2")
            .city("city2")
            .district("district2")
            .state("state2")
            .region("region2")
            .country("country2")
            .createdBy("createdBy2")
            .modifiedBy("modifiedBy2");
    }

    public static Address getAddressRandomSampleGenerator() {
        return new Address()
            .id(UUID.randomUUID().toString())
            .digitalAddress(UUID.randomUUID().toString())
            .streetAddress(UUID.randomUUID().toString())
            .areaCode(UUID.randomUUID().toString())
            .town(UUID.randomUUID().toString())
            .city(UUID.randomUUID().toString())
            .district(UUID.randomUUID().toString())
            .state(UUID.randomUUID().toString())
            .region(UUID.randomUUID().toString())
            .country(UUID.randomUUID().toString())
            .createdBy(UUID.randomUUID().toString())
            .modifiedBy(UUID.randomUUID().toString());
    }
}
