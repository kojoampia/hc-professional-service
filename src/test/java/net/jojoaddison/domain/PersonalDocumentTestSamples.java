package net.jojoaddison.domain;

import java.util.UUID;

public class PersonalDocumentTestSamples {

    public static PersonalDocument getPersonalDocumentSample1() {
        return new PersonalDocument().id("id1").name("name1").profileId("profileId1").lastModifiedBy("lastModifiedBy1");
    }

    public static PersonalDocument getPersonalDocumentSample2() {
        return new PersonalDocument().id("id2").name("name2").profileId("profileId2").lastModifiedBy("lastModifiedBy2");
    }

    public static PersonalDocument getPersonalDocumentRandomSampleGenerator() {
        return new PersonalDocument()
            .id(UUID.randomUUID().toString())
            .name(UUID.randomUUID().toString())
            .profileId(UUID.randomUUID().toString())
            .lastModifiedBy(UUID.randomUUID().toString());
    }
}
