package net.jojoaddison.domain;

import java.util.UUID;

public class TeamTestSamples {

    public static Team getTeamSample1() {
        return new Team()
            .id("id1")
            .name("name1")
            .description("description1")
            .members("members1")
            .supervisor("supervisor1")
            .manager("manager1");
    }

    public static Team getTeamSample2() {
        return new Team()
            .id("id2")
            .name("name2")
            .description("description2")
            .members("members2")
            .supervisor("supervisor2")
            .manager("manager2");
    }

    public static Team getTeamRandomSampleGenerator() {
        return new Team()
            .id(UUID.randomUUID().toString())
            .name(UUID.randomUUID().toString())
            .description(UUID.randomUUID().toString())
            .members(UUID.randomUUID().toString())
            .supervisor(UUID.randomUUID().toString())
            .manager(UUID.randomUUID().toString());
    }
}
