package net.jojoaddison.domain;

import java.util.UUID;

public class IDocumentTestSamples {

    public static IDocument getHCDocumentSample1() {
        return new IDocument().id("id1").name("name1").profileId("profileId1").lastModifiedBy("lastModifiedBy1");
    }

    public static IDocument getHCDocumentSample2() {
        return new IDocument().id("id2").name("name2").profileId("profileId2").lastModifiedBy("lastModifiedBy2");
    }

    public static IDocument getHCDocumentRandomSampleGenerator() {
        return new IDocument()
            .id(UUID.randomUUID().toString())
            .name(UUID.randomUUID().toString())
            .profileId(UUID.randomUUID().toString())
            .lastModifiedBy(UUID.randomUUID().toString());
    }
}
