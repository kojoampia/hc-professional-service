package net.jojoaddison.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the two authority arrays name, asserted rather than read.
 *
 * <p>{@link AuthoritiesConstants#CLINICAL_MUTATION} and {@link AuthoritiesConstants#CLINICAL_AND_ADMIN}
 * are the whole of this service's own authorization rules, and {@code ClinicalAuthorityMatrixIT}
 * exercises them through real requests. What that test cannot do is notice a <em>removal</em>: it
 * names a role per case, so an authority taken out of an array leaves the cases that covered it
 * passing for the wrong reason, or leaves no case at all. This class writes the memberships down.
 *
 * <p>Its sibling is {@code hc-patient}'s {@code AuthoritiesConstantsUnitTest}, and the pair is the
 * only thing holding the two repositories' spelling of "any clinician" together — they share no
 * artefact and cannot. The gateway carries a third copy for {@code /services/**}. <b>The three no
 * longer say the same thing about {@code ROLE_ANGEL}, and must not.</b> hc-patient keeps the authority
 * and asserts it is outside its clinical sets; this stack removed it entirely on 2026-09-08 and
 * asserts it is nowhere at all — see {@link #noPrivilegeSetInThisClassNamesTheCareAngelAuthority}.
 */
class AuthoritiesConstantsUnitTest {

    @Test
    void theWiderReadSetIsTheAdministratorAndTheEightClinicalDisciplines() {
        // Literal strings, not the constants beside them: a typo in a constant would be copied into
        // the expectation and assert nothing. These are the exact values that arrive in an `auth`
        // claim, and hc-patient's own test writes the same eight down for the same reason.
        assertThat(AuthoritiesConstants.CLINICAL_AND_ADMIN).containsExactlyInAnyOrder(
            "ROLE_ADMIN",
            "ROLE_DOCTOR",
            "ROLE_NURSE",
            "ROLE_PARAMEDIC",
            "ROLE_PHARMACIST",
            "ROLE_THERAPIST",
            "ROLE_CARER",
            "ROLE_CHEMIST",
            "ROLE_TECHNICIAN"
        );
    }

    @Test
    void theMutationMatrixIsTheAdministratorDoctorAndFourDisciplines() {
        // Unchanged by item 30 and by item 44, and written down so neither narrowing is mistaken for
        // a change to this one. Carer, chemist and technician are read-only in v1 -- a rule about
        // clinical WRITES -- and an angel was outside CLINICAL_MUTATION on those grounds long before
        // it left the read set on different ones and the stack on a third.
        // ClinicalAuthorityMatrixIT proves the refusals through real POSTs.
        assertThat(AuthoritiesConstants.CLINICAL_MUTATION).containsExactlyInAnyOrder(
            "ROLE_ADMIN",
            "ROLE_DOCTOR",
            "ROLE_NURSE",
            "ROLE_PARAMEDIC",
            "ROLE_PHARMACIST",
            "ROLE_THERAPIST"
        );
    }

    @Test
    void theBaseUserAuthorityIsInNeither() {
        // ROLE_USER's absence is what makes CLINICAL_AND_ADMIN the second layer behind the gateway
        // rather than a copy of it: an applicant here holds it and nothing else, and so does a
        // caller from either sibling stack, since the three gateways share one signing key and this
        // service validates no issuer.
        assertThat(AuthoritiesConstants.CLINICAL_AND_ADMIN).doesNotContain(AuthoritiesConstants.USER, AuthoritiesConstants.PATIENT);
        assertThat(AuthoritiesConstants.CLINICAL_MUTATION).doesNotContain(AuthoritiesConstants.USER, AuthoritiesConstants.PATIENT);
    }

    /**
     * The care angel does not exist in this subsystem — not as a constant, and not inside either
     * authority array.
     *
     * <p><b>This replaces three named tests and is deliberately wider than any of them.</b> Until
     * 2026-09-08 there was a {@code theCareAngelAuthorityIsNotAmongThem} naming
     * {@code CLINICAL_AND_ADMIN}, a {@code doesNotContain} clause on the mutation matrix, and a
     * {@code theCareAngelAuthorityStillExists} asserting the constant. Item 44 removed the authority
     * from this stack entirely — an angel supports one named patient, and hc-patient owns the concept —
     * so the last is false and the other two would have gone with the constant they referenced, taking
     * the guard away with them. That is the risk worth naming: those tests existed to stop somebody
     * putting {@code ROLE_ANGEL} back into a privilege set, and deleting the constant is precisely the
     * change that makes putting it back feel like closing a gap.
     *
     * <p><b>It reads the class rather than a list of field names.</b> A guard that names its own
     * coverage stops covering things — the reason {@code JhipsterEnumFieldValuesTest} derives its
     * expectations and hc-admin had eight endpoints go unpaginated behind a test asserting a literal
     * list of paths. A third privilege array added next year is checked on the day it is written, with
     * nobody having edited this file. The literal is spelled out because there is no longer a constant
     * to reference, which is the point.
     *
     * <p>What it cannot see is a bare {@code "ROLE_ANGEL"} written straight into a matcher in
     * {@code SecurityConfiguration} or a {@code @PreAuthorize}. {@code ClinicalAuthorityMatrixIT}
     * covers that from the other side, by sending real requests with a {@code ROLE_ANGEL} token.
     */
    @Test
    void noPrivilegeSetInThisClassNamesTheCareAngelAuthority() {
        List<String> offenders = new ArrayList<>();

        for (Field field : AuthoritiesConstants.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Object value;
            try {
                value = field.get(null);
            } catch (IllegalAccessException e) {
                throw new AssertionError("could not read " + field.getName(), e);
            }
            if (value instanceof String authority && "ROLE_ANGEL".equals(authority)) {
                offenders.add(field.getName() + " declares ROLE_ANGEL");
            } else if (value instanceof String[] set && Arrays.asList(set).contains("ROLE_ANGEL")) {
                offenders.add(field.getName() + " contains ROLE_ANGEL");
            }
        }

        assertThat(offenders)
            .as(
                "ROLE_ANGEL is hc-patient's authority and has no meaning in this subsystem (docs/backlog.md item 44). " +
                "A token carrying it may still arrive over the shared signing key, or be held by an account created " +
                "before the removal; it must go on granting nothing"
            )
            .isEmpty();
    }
}
