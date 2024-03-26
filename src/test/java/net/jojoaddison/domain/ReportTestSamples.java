package net.jojoaddison.domain;

import java.util.UUID;

public class ReportTestSamples {

    public static Report getReportSample1() {
        return new Report()
            .id("id1")
            .category("category1")
            .description("description1")
            .name("name1")
            .url("url1")
            .professionalId("professionalId1")
            .createdBy("createdBy1")
            .modifiedBy("modifiedBy1");
    }

    public static Report getReportSample2() {
        return new Report()
            .id("id2")
            .category("category2")
            .description("description2")
            .name("name2")
            .url("url2")
            .professionalId("professionalId2")
            .createdBy("createdBy2")
            .modifiedBy("modifiedBy2");
    }

    public static Report getReportRandomSampleGenerator() {
        return new Report()
            .id(UUID.randomUUID().toString())
            .category(UUID.randomUUID().toString())
            .description(UUID.randomUUID().toString())
            .name(UUID.randomUUID().toString())
            .url(UUID.randomUUID().toString())
            .professionalId(UUID.randomUUID().toString())
            .createdBy(UUID.randomUUID().toString())
            .modifiedBy(UUID.randomUUID().toString());
    }
}
