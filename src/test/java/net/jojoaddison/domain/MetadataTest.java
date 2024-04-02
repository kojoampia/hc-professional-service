package net.jojoaddison.domain;

import static net.jojoaddison.domain.MetadataTestSamples.*;
import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.web.rest.TestUtil;
import org.junit.jupiter.api.Test;

class MetadataTest {

    @Test
    void equalsVerifier() throws Exception {
        TestUtil.equalsVerifier(Metadata.class);
        Metadata metadata1 = getMetadataSample1();
        Metadata metadata2 = new Metadata();
        assertThat(metadata1).isNotEqualTo(metadata2);

        metadata2.setId(metadata1.getId());
        assertThat(metadata1).isEqualTo(metadata2);

        metadata2 = getMetadataSample2();
        assertThat(metadata1).isNotEqualTo(metadata2);
    }
}
