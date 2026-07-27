package net.jojoaddison.domain;

import static net.jojoaddison.domain.PersonalDocumentTestSamples.*;
import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.web.rest.TestUtil;
import org.junit.jupiter.api.Test;

class PersonalDocumentTest {

    @Test
    void equalsVerifier() throws Exception {
        TestUtil.equalsVerifier(PersonalDocument.class);
        PersonalDocument personalDocument1 = getPersonalDocumentSample1();
        PersonalDocument personalDocument2 = new PersonalDocument();
        assertThat(personalDocument1).isNotEqualTo(personalDocument2);

        personalDocument2.setId(personalDocument1.getId());
        assertThat(personalDocument1).isEqualTo(personalDocument2);

        personalDocument2 = getPersonalDocumentSample2();
        assertThat(personalDocument1).isNotEqualTo(personalDocument2);
    }
}
