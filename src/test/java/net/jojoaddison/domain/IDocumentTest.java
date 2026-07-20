package net.jojoaddison.domain;

import static net.jojoaddison.domain.IDocumentTestSamples.*;
import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.web.rest.TestUtil;
import org.junit.jupiter.api.Test;

class IDocumentTest {

    @Test
    void equalsVerifier() throws Exception {
        TestUtil.equalsVerifier(IDocument.class);
        IDocument hCDocument1 = getHCDocumentSample1();
        IDocument hCDocument2 = new IDocument();
        assertThat(hCDocument1).isNotEqualTo(hCDocument2);

        hCDocument2.setId(hCDocument1.getId());
        assertThat(hCDocument1).isEqualTo(hCDocument2);

        hCDocument2 = getHCDocumentSample2();
        assertThat(hCDocument1).isNotEqualTo(hCDocument2);
    }
}
