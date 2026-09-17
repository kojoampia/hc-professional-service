package net.jojoaddison.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;
import net.jojoaddison.domain.enumeration.OnboardingStatus;

/**
 * One professional, composed for a sibling product that knows them only by {@code accountId}
 * (backlog.md item 51).
 *
 * <p>hc-admin's directory holds link rows that know a clinician exists and can never learn who they
 * are: its consumers see {@code entityType}, {@code entityId} and {@code accountId} and nothing
 * else. The fields it needs live in three collections here — {@code Profile}, {@code
 * ProfessionalApplication} and {@code PersonalDocument} — so three calls against three resources
 * with three authority rules would be hc-admin reimplementing this service's model. This is the
 * projection instead, and it is the mirror of the narrow {@code GeographicSpace} read hc-admin
 * already serves this stack.
 *
 * <p><b>Every field is nullable and absent when unknown</b>, because a directory record is assembled
 * from rows that arrive at different times. An applicant has an application before they have a
 * profile; a clinician created by admin invitation has a profile before they have an application.
 * Present-and-null would make "this clinician has no licence on file" and "this service does not
 * know" the same value, which is the distinction the consumer has to act on. <b>There is no record
 * at all for an account this service has never heard of</b> — the endpoint answers 404 rather than a
 * shape with every field missing.
 *
 * <p><b>The {@code NON_NULL} annotation below is a declaration of that, and is not what enforces
 * it.</b> Measured: deleting it changes no response — with the annotation gone the absent fields are
 * still omitted, so a default underneath already does this, and it is not one this repository sets
 * ({@code JacksonConfiguration} registers two modules and no inclusion; no {@code
 * spring.jackson.default-property-inclusion} exists in any config file here). It stays because the
 * contract must not rest on a framework default nobody chose and nothing here records; what actually
 * holds the behaviour is {@code DirectoryRecordIT}, which asserts absence field by field. Said
 * plainly rather than left implied, because an annotation that reads as load-bearing and is not is
 * how a later upgrade changes a published contract with every test still green.
 *
 * <h2>There is no licence number here, and there never will be</h2>
 *
 * <p>This subsystem does not hold one. {@code PersonalDocument} of type {@code LICENSE} carries a
 * name, a checksum, an expiry date, a verification status and the supersession pair, and no
 * identifier of the credential itself; the free-text fields that could have carried one by
 * convention do not; and the onboarding wizard never asks for one. hc-admin's {@code
 * Professional.licenceNumber} cannot be filled from this stack, today or after this endpoint, and
 * the recommendation from this side is that it stop requiring one. What is offered instead is
 * {@link #licenceVerified} — <em>a fact about a credential</em> — which is the same answer item 47
 * reached from the event side, where {@code ProfileStatus} carries a verification flag for exactly
 * this reason.
 *
 * @param accountId         the gateway {@code User.id} the record was asked for, echoed so a
 *                          consumer batching reads can key the answer without tracking the request.
 * @param firstName         {@code Profile.firstName}; absent when no profile exists yet.
 * @param lastName          {@code Profile.lastName}; absent when no profile exists yet.
 * @param role              {@code ProfessionalApplication.requestedRole}, absent when there is no
 *                          application. <b>This stack's vocabulary, not hc-admin's</b> — see below.
 * @param onboardingStatus  where the application has got to. Carried <em>because</em> {@code role}
 *                          is a requested role rather than a granted one: a consumer that reads the
 *                          role alone would file a rejected applicant into its professional
 *                          directory as a nurse, and nothing downstream would look wrong. A
 *                          clinician is live only at {@code ACTIVE}.
 * @param licenceVerified   whether this clinician holds a current verified licence — the very
 *                          predicate this service's own activation and reactivation gates read, so
 *                          the consumer's record agrees with those gates by construction rather
 *                          than by a second implementation. <b>Absent means no licence on file at
 *                          all</b>; {@code false} means one is on file and is not currently
 *                          verified — expired, superseded into nothing, rejected or still awaiting
 *                          a reviewer. The distinction matters to a consumer deciding whether to
 *                          chase a clinician or a reviewer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DirectoryRecordDTO(
    String accountId,
    String firstName,
    String lastName,
    String role,
    OnboardingStatus onboardingStatus,
    Boolean licenceVerified
)
    implements Serializable {
    /**
     * The values {@link #role} can carry, and the warning that goes with them.
     *
     * <p>{@code ProfessionalApplication.requestedRole} is a bare {@code String} with no validation,
     * so this is a description of what is stored rather than a guarantee the type enforces. Measured
     * across every application on the quality stack it is uniform, and it is exactly the
     * {@code ROLE_}-prefixed authority names this subsystem declares in {@code
     * AuthoritiesConstants}: {@code ROLE_DOCTOR}, {@code ROLE_NURSE}, {@code ROLE_PARAMEDIC},
     * {@code ROLE_PHARMACIST}, {@code ROLE_THERAPIST}, {@code ROLE_CARER}, {@code ROLE_CHEMIST},
     * {@code ROLE_TECHNICIAN}.
     *
     * <p><b>This is deliberately not hc-admin's vocabulary and must not be mapped onto it
     * blindly.</b> Their {@code ProfessionalRole} is an enum of unprefixed names; it spells the
     * carer discipline {@code CAREGIVER}; and it has no value at all for the pharmacist, chemist and
     * technician disciplines. So a mapping table over it cannot be total, and a consumer needs a
     * <em>defined refusal</em> for a role it does not model — leaving the link without a record is
     * honest, defaulting one onto the nearest value is not. The vocabulary stays this service's: a
     * shared enum across two products is a single decision that two release cycles have to agree
     * on, which is the coupling this projection exists to avoid.
     *
     * <p>Declared as data rather than prose so that a reader gets the list from the code, and
     * deliberately not derived from {@code AuthoritiesConstants}: this is a statement about what
     * has been <em>stored</em>, and an authority added tomorrow does not retroactively appear in
     * rows written yesterday.
     */
    public static final java.util.List<String> DOCUMENTED_ROLE_VALUES = java.util.List.of(
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
