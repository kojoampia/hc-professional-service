package net.jojoaddison.service.dto;

import java.io.Serializable;
import java.util.List;
import net.jojoaddison.domain.enumeration.OnboardingStatus;

/**
 * How far an applicant has got, computed by the server.
 *
 * <p>The browser is deliberately not trusted to work this out (see
 * {@code professional-onboarding-workflow.md} § "Onboarding state events and the completion
 * contract"). The same figure drives three things — the meter on {@code /account/profile}, the gate
 * on the transition to {@code ACTIVE}, and the post-sign-in redirect — and a client-side percentage
 * can read 100% while the server still refuses to advance the application. One definition, one
 * source.
 *
 * @param percent      0–100, {@code done / requirements.size()} rounded to the nearest whole.
 * @param complete     every requirement satisfied; what the {@code ACTIVE} gate actually reads.
 * @param status       where the application has got to, or {@code null} for an account that has no
 *                     application at all — the state every clinician created by admin invitation
 *                     starts in. Deliberately <em>not</em> derivable from {@code complete}: ACTIVE
 *                     requires completeness <em>and</em> admin vetting, so a finished profile that
 *                     nobody has reviewed is {@code complete = true} with a status well short of
 *                     ACTIVE. Callers asking "is this clinician live" must read this, not that.
 * @param requirements each requirement and whether it is met, in display order, so the client can
 *                     say <em>what</em> is missing without re-deriving the rules.
 */
public record OnboardingProgressDTO(int percent, boolean complete, OnboardingStatus status, List<Requirement> requirements)
    implements Serializable {
    /**
     * @param key  stable identifier; the client maps it to a translated label in four languages, so
     *             it must not carry human-readable text.
     * @param done whether this requirement is satisfied.
     */
    public record Requirement(String key, boolean done) implements Serializable {}
}
