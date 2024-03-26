package net.jojoaddison.domain;

import static net.jojoaddison.domain.DocumentTestSamples.getDocumentSample1;
import static net.jojoaddison.domain.DocumentTestSamples.getDocumentSample2;
import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.web.rest.TestUtil;
import org.junit.jupiter.api.Test;

class HCDocumentTest {

    @Test
    void equalsVerifier() throws Exception {
        TestUtil.equalsVerifier(HCDocument.class);
        HCDocument document1 = getDocumentSample1();
        HCDocument document2 = new HCDocument();
        assertThat(document1).isNotEqualTo(document2);

        document2.setId(document1.getId());
        assertThat(document1).isEqualTo(document2);

        document2 = getDocumentSample2();
        assertThat(document1).isNotEqualTo(document2);
    }
}
