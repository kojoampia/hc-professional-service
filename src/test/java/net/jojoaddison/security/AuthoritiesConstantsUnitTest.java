package net.jojoaddison.security;

import static org.assertj.core.api.Assertions.assertThat;

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
 * artefact and cannot. The gateway carries a third copy for {@code /services/**}.
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
    void theCareAngelAuthorityIsNotAmongThem() {
        // THE ESTATE DECIDED ON 2026-09-06 THAT AN ANGEL IS NOT A CLINICAL DISCIPLINE (backlog
        // item 30). A discipline is a standing capability; an angel's authority is an ACTIVE
        // CareDelegation over ONE named patient, held in hc-patient and re-read per request so that
        // a revocation takes effect on the next call rather than when a rememberMe token expires.
        // A role check can carry none of that -- not the patient, not the dates, not the
        // revocability -- so admitting ROLE_ANGEL to a clinical set silently converts a scoped grant
        // into an unscoped one.
        //
        // The operative half of the change is the gateway's: ROLE_ANGEL no longer opens
        // /services/**. This array narrows with it so that this service does not name one authority
        // more than the rule admitting callers to it at all.
        //
        // Adding it back would LOOK like closing a gap, because ROLE_ANGEL is still a real seeded
        // authority and still one of the nine values web/ and mobile/ enumerate. It is not a gap.
        assertThat(AuthoritiesConstants.CLINICAL_AND_ADMIN).doesNotContain(AuthoritiesConstants.ANGEL);
    }

    @Test
    void theCareAngelAuthorityWasNeverInTheMutationMatrixEither() {
        // Unchanged by item 30 and asserted so the two narrowings are not confused for one. Carer,
        // chemist and technician are read-only in v1 -- a rule about clinical WRITES -- and an angel
        // was outside CLINICAL_MUTATION on those grounds long before it left the read set on
        // different ones. ClinicalAuthorityMatrixIT proves the refusal through a real POST.
        assertThat(AuthoritiesConstants.CLINICAL_MUTATION)
            .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_PARAMEDIC", "ROLE_PHARMACIST", "ROLE_THERAPIST")
            .doesNotContain(AuthoritiesConstants.ANGEL);
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

    @Test
    void theCareAngelAuthorityStillExists() {
        // The narrowing is a scope change, not a retirement. ROLE_ANGEL is still seeded by the
        // gateway's InitialSetupMigration, still assignable, still carried in a token, and still one
        // of the nine values web/ and mobile/ enumerate -- an angel signs in and keeps everything the
        // .authenticated() rules cover: onboarding, their own inbox, notifications, absences.
        // Deleting the constant would be a different and much larger change; this asserts it was not
        // made by accident while removing the name from an array two lines away.
        assertThat(AuthoritiesConstants.ANGEL).isEqualTo("ROLE_ANGEL");
    }
}
