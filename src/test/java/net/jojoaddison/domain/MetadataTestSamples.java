package net.jojoaddison.domain;

import java.util.UUID;

public class MetadataTestSamples {

    public static Metadata getMetadataSample1() {
        return new Metadata().id("id1").createdBy("createdBy1").modifiedBy("modifiedBy1").data("data1");
    }

    public static Metadata getMetadataSample2() {
        return new Metadata().id("id2").createdBy("createdBy2").modifiedBy("modifiedBy2").data("data2");
    }

    public static Metadata getMetadataRandomSampleGenerator() {
        return new Metadata()
            .id(UUID.randomUUID().toString())
            .createdBy(UUID.randomUUID().toString())
            .modifiedBy(UUID.randomUUID().toString())
            .data(UUID.randomUUID().toString());
    }
}
