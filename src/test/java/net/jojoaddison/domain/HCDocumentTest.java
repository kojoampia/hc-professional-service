package net.jojoaddison.domain;

import static net.jojoaddison.domain.HCDocumentTestSamples.*;
import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.web.rest.TestUtil;
import org.junit.jupiter.api.Test;

class HCDocumentTest {

    @Test
    void equalsVerifier() throws Exception {
        TestUtil.equalsVerifier(HCDocument.class);
        HCDocument hCDocument1 = getHCDocumentSample1();
        HCDocument hCDocument2 = new HCDocument();
        assertThat(hCDocument1).isNotEqualTo(hCDocument2);

        hCDocument2.setId(hCDocument1.getId());
        assertThat(hCDocument1).isEqualTo(hCDocument2);

        hCDocument2 = getHCDocumentSample2();
        assertThat(hCDocument1).isNotEqualTo(hCDocument2);
    }
}
