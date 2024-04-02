package net.jojoaddison.domain;

import static net.jojoaddison.domain.HCCredentialTestSamples.*;
import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.web.rest.TestUtil;
import org.junit.jupiter.api.Test;

class HCCredentialTest {

    @Test
    void equalsVerifier() throws Exception {
        TestUtil.equalsVerifier(HCCredential.class);
        HCCredential hCCredential1 = getHCCredentialSample1();
        HCCredential hCCredential2 = new HCCredential();
        assertThat(hCCredential1).isNotEqualTo(hCCredential2);

        hCCredential2.setId(hCCredential1.getId());
        assertThat(hCCredential1).isEqualTo(hCCredential2);

        hCCredential2 = getHCCredentialSample2();
        assertThat(hCCredential1).isNotEqualTo(hCCredential2);
    }
}
