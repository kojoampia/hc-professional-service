package net.jojoaddison.domain;

import static net.jojoaddison.domain.RosterTestSamples.*;
import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.web.rest.TestUtil;
import org.junit.jupiter.api.Test;

class RosterTest {

    @Test
    void equalsVerifier() throws Exception {
        TestUtil.equalsVerifier(Roster.class);
        Roster roster1 = getRosterSample1();
        Roster roster2 = new Roster();
        assertThat(roster1).isNotEqualTo(roster2);

        roster2.setId(roster1.getId());
        assertThat(roster1).isEqualTo(roster2);

        roster2 = getRosterSample2();
        assertThat(roster1).isNotEqualTo(roster2);
    }
}
