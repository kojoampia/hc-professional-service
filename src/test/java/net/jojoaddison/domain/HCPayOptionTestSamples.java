package net.jojoaddison.domain;

import java.util.UUID;

public class HCPayOptionTestSamples {

    public static HCPayOption getHCPayOptionSample1() {
        return new HCPayOption().id("id1").type("type1").userID("userID1").metadata("metadata1");
    }

    public static HCPayOption getHCPayOptionSample2() {
        return new HCPayOption().id("id2").type("type2").userID("userID2").metadata("metadata2");
    }

    public static HCPayOption getHCPayOptionRandomSampleGenerator() {
        return new HCPayOption()
            .id(UUID.randomUUID().toString())
            .type(UUID.randomUUID().toString())
            .userID(UUID.randomUUID().toString())
            .metadata(UUID.randomUUID().toString());
    }
}
