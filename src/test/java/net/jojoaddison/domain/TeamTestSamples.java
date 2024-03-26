package net.jojoaddison.domain;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class TeamTestSamples {

    public static Team getTeamSample1() {
        Set<Profile> members = new HashSet<Profile>();
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());

        return new Team()
            .id("id1")
            .name("name1")
            .description("description1")
            .members(members)
            .supervisor(ProfileTestSamples.getProfileRandomSampleGenerator())
            .manager(ProfileTestSamples.getProfileRandomSampleGenerator());
    }

    public static Team getTeamSample2() {
        Set<Profile> members = new HashSet<Profile>();
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());

        return new Team()
            .id("id2")
            .name("name2")
            .description("description2")
            .members(members)
            .supervisor(ProfileTestSamples.getProfileRandomSampleGenerator())
            .manager(ProfileTestSamples.getProfileRandomSampleGenerator());
    }

    public static Team getTeamRandomSampleGenerator() {
        Set<Profile> members = new HashSet<Profile>();
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());
        members.add(ProfileTestSamples.getProfileRandomSampleGenerator());

        return new Team()
            .id(UUID.randomUUID().toString())
            .name(UUID.randomUUID().toString())
            .description(UUID.randomUUID().toString())
            .members(members)
            .supervisor(ProfileTestSamples.getProfileRandomSampleGenerator())
            .manager(ProfileTestSamples.getProfileRandomSampleGenerator());
    }
}
