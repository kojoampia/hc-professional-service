package net.jojoaddison.domain;

import java.util.UUID;

public class TaskTestSamples {

    public static Task getTaskSample1() {
        return new Task()
            .id("id1")
            .name("name1")
            .description("description1")
            .attendantId("attendantId1")
            .teamId("teamId1")
            .patientId("patientId1")
            .attendant("attendant1")
            .createdBy("createdBy1")
            .modifiedBy("modifiedBy1");
    }

    public static Task getTaskSample2() {
        return new Task()
            .id("id2")
            .name("name2")
            .description("description2")
            .attendantId("attendantId2")
            .teamId("teamId2")
            .patientId("patientId2")
            .attendant("attendant2")
            .createdBy("createdBy2")
            .modifiedBy("modifiedBy2");
    }

    public static Task getTaskRandomSampleGenerator() {
        return new Task()
            .id(UUID.randomUUID().toString())
            .name(UUID.randomUUID().toString())
            .description(UUID.randomUUID().toString())
            .attendantId(UUID.randomUUID().toString())
            .teamId(UUID.randomUUID().toString())
            .patientId(UUID.randomUUID().toString())
            .attendant(UUID.randomUUID().toString())
            .createdBy(UUID.randomUUID().toString())
            .modifiedBy(UUID.randomUUID().toString());
    }
}
