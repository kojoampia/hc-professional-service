package net.jojoaddison.domain;

import java.util.UUID;

public class HCCredentialTestSamples {

    public static HCCredential getHCCredentialSample1() {
        return new HCCredential().id("id1").email("email1").phoneNumber("phoneNumber1").password("password1").role("role1");
    }

    public static HCCredential getHCCredentialSample2() {
        return new HCCredential().id("id2").email("email2").phoneNumber("phoneNumber2").password("password2").role("role2");
    }

    public static HCCredential getHCCredentialRandomSampleGenerator() {
        return new HCCredential()
            .id(UUID.randomUUID().toString())
            .email(UUID.randomUUID().toString())
            .phoneNumber(UUID.randomUUID().toString())
            .password(UUID.randomUUID().toString())
            .role(UUID.randomUUID().toString());
    }
}
