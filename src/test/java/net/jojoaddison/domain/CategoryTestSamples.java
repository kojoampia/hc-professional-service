package net.jojoaddison.domain;

import java.util.UUID;

public class CategoryTestSamples {

    public static Category getCategorySample1() {
        return new Category().id("id1").name("name1").description("description1").createdBy("createdBy1").modifiedBy("modifiedBy1");
    }

    public static Category getCategorySample2() {
        return new Category().id("id2").name("name2").description("description2").createdBy("createdBy2").modifiedBy("modifiedBy2");
    }

    public static Category getCategoryRandomSampleGenerator() {
        return new Category()
            .id(UUID.randomUUID().toString())
            .name(UUID.randomUUID().toString())
            .description(UUID.randomUUID().toString())
            .createdBy(UUID.randomUUID().toString())
            .modifiedBy(UUID.randomUUID().toString());
    }
}
