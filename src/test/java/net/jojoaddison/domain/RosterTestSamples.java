package net.jojoaddison.domain;

import java.util.UUID;

public class RosterTestSamples {

    public static Roster getRosterSample1() {
        return new Roster()
            .id("id1")
            .name("name1")
            .description("description1")
            .professionalId("professionalId1")
            .tasks("tasks1")
            .createdDate("createdDate1")
            .createdBy("createdBy1")
            .modifiedBy("modifiedBy1");
    }

    public static Roster getRosterSample2() {
        return new Roster()
            .id("id2")
            .name("name2")
            .description("description2")
            .professionalId("professionalId2")
            .tasks("tasks2")
            .createdDate("createdDate2")
            .createdBy("createdBy2")
            .modifiedBy("modifiedBy2");
    }

    public static Roster getRosterRandomSampleGenerator() {
        return new Roster()
            .id(UUID.randomUUID().toString())
            .name(UUID.randomUUID().toString())
            .description(UUID.randomUUID().toString())
            .professionalId(UUID.randomUUID().toString())
            .tasks(UUID.randomUUID().toString())
            .createdDate(UUID.randomUUID().toString())
            .createdBy(UUID.randomUUID().toString())
            .modifiedBy(UUID.randomUUID().toString());
    }
}
