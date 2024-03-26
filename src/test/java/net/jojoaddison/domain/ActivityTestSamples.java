package net.jojoaddison.domain;

import java.util.UUID;

public class ActivityTestSamples {

    public static Activity getActivitySample1() {
        return new Activity()
            .id("id1")
            .name("name1")
            .description("description1")
            .patientId("patientId1")
            .createdBy("createdBy1")
            .modifiedBy("modifiedBy1");
    }

    public static Activity getActivitySample2() {
        return new Activity()
            .id("id2")
            .name("name2")
            .description("description2")
            .patientId("patientId2")
            .createdBy("createdBy2")
            .modifiedBy("modifiedBy2");
    }

    public static Activity getActivityRandomSampleGenerator() {
        return new Activity()
            .id(UUID.randomUUID().toString())
            .name(UUID.randomUUID().toString())
            .description(UUID.randomUUID().toString())
            .patientId(UUID.randomUUID().toString())
            .createdBy(UUID.randomUUID().toString())
            .modifiedBy(UUID.randomUUID().toString());
    }
}
