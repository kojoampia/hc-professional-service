package net.jojoaddison.domain;

import static net.jojoaddison.domain.HCPayOptionTestSamples.*;
import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.web.rest.TestUtil;
import org.junit.jupiter.api.Test;

class HCPayOptionTest {

    @Test
    void equalsVerifier() throws Exception {
        TestUtil.equalsVerifier(HCPayOption.class);
        HCPayOption hCPayOption1 = getHCPayOptionSample1();
        HCPayOption hCPayOption2 = new HCPayOption();
        assertThat(hCPayOption1).isNotEqualTo(hCPayOption2);

        hCPayOption2.setId(hCPayOption1.getId());
        assertThat(hCPayOption1).isEqualTo(hCPayOption2);

        hCPayOption2 = getHCPayOptionSample2();
        assertThat(hCPayOption1).isNotEqualTo(hCPayOption2);
    }
}
