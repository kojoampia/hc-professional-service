package net.jojoaddison.service.dto;

import java.io.Serializable;
import java.util.List;
import net.jojoaddison.domain.enumeration.ProfileStatus;

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
 * <h2>⭐ Two readings of the same state, since backlog.md row 230</h2>
 *
 * <p>{@link #requirements} is the fine-grained list this contract opened with. {@link #steps} is
 * {@code profile.md}'s four coarse steps, which row 230 adds as the wire the onboarding wizard
 * renders: <i>"The wire carries the four steps coarsely — {@code account}, {@code profile},
 * {@code documents}, {@code consent}, one boolean each"</i>, with the form validating every
 * requirement client-side so the applicant still sees <em>which</em> gaps remain.
 *
 * <p><b>{@code steps} is added, not substituted, and the reason is the house rule rather than
 * taste.</b> Add before removing, and remove the consumer before the producer: {@code web/} (unit B)
 * and {@code mobile/} (unit C) both read {@link #requirements} today, and {@code mobile/}'s copy is a
 * bare union type that nothing can enumerate at runtime. Dropping the list now would blank the
 * meter in two shipped clients — exactly as {@code POST /api/account} was kept deprecated through
 * T4 rather than deleted under its caller.
 *
 * @param percent      0–100, {@code done / requirements.size()} rounded to the nearest whole.
 * @param complete     every requirement satisfied; what the {@code ACTIVE} gate actually reads.
 *                     ⚠ <b>Over {@link #requirements}, not over {@link #steps}</b> — see
 *                     {@link Steps}, which explains why step 1 is reported and not required.
 * @param status       where the application has got to, or {@code null} for an account that has no
 *                     application at all — the state every clinician created by admin invitation
 *                     starts in. Deliberately <em>not</em> derivable from {@code complete}: ACTIVE
 *                     requires completeness <em>and</em> admin vetting, so a finished profile that
 *                     nobody has reviewed is {@code complete = true} with a status well short of
 *                     ACTIVE. Callers asking "is this clinician live" must read this, not that.
 * @param requirements each requirement and whether it is met, in display order, so the client can
 *                     say <em>what</em> is missing without re-deriving the rules.
 * @param steps        {@code profile.md}'s four steps, one boolean each — see {@link Steps}.
 */
public record OnboardingProgressDTO(int percent, boolean complete, ProfileStatus status, List<Requirement> requirements, Steps steps)
    implements Serializable {
    /**
     * @param key  stable identifier; the client maps it to a translated label in four languages, so
     *             it must not carry human-readable text.
     * @param done whether this requirement is satisfied.
     */
    public record Requirement(String key, boolean done) implements Serializable {}

    /**
     * {@code profile.md}'s four onboarding steps, one boolean each — the coarse wire row 230 decided
     * on.
     *
     * <p>{@code profile.md} § Gap Update: <i>"The progress meter covers exactly these 4 steps and all
     * their respective requirements."</i> Each boolean below is the conjunction of that step's
     * requirements, and three of the four are derived from {@link #requirements} rather than
     * evaluated a second time — {@code OnboardingService.progressFor} is where that derivation lives,
     * so the coarse reading and the fine one cannot disagree about a predicate.
     *
     * <h2>⛔ {@link #account} is REPORTED and not REQUIRED, and conflating the two breaks activation</h2>
     *
     * <p>{@link OnboardingProgressDTO#complete} is read by two things that refuse work:
     * {@code OnboardingService.requireCompleteProfile} — the {@code ACTIVE} gate, a 409 an
     * administrator meets — and {@code publishProfileStatus}, which tells hc-admin whether a
     * clinician's profile is complete. <b>Step 1's boolean depends on a Kafka frame having arrived</b>
     * (see {@code AccountCompleteness}), so folding it into {@code complete} would mean that with
     * {@code application.kafka.enabled=false}, or before an account's first write after this shipped,
     * <b>no clinician in the estate could be activated</b> and every one of them would be reported
     * incomplete to hc-admin. A meter that cannot be completed is worse than a meter that is a step
     * short.
     *
     * <p>So the division is deliberate and is the same one {@code OnboardingService.java:412-417}
     * already drew before any of this was built: <i>"what this gate enforces is steps 2, 3 and 4 … and
     * the client is what keeps step 1 ahead of step 2"</i>. What row 230 changes is that the server now
     * <em>says</em> where step 1 stands; it still does not <em>enforce</em> it.
     *
     * @param account   step 1 — all four of {@code firstName}, {@code lastName}, {@code langKey} and
     *                  {@code imageUrl} on the gateway's {@code User}, as last announced by
     *                  {@code AccountDetailsUpdated}. ⚠ {@code false} also means "no frame has
     *                  arrived yet"; the two are indistinguishable by design.
     * @param profile   step 2 — the {@code profile}, {@code address} and {@code nextOfKin}
     *                  requirements, all three.
     * @param documents step 3 — <b>all four</b> mandatory documents present and live:
     *                  {@code certificate}, {@code license} (with an expiry date), {@code identity},
     *                  {@code photo}. ⚠ <b>One document is not four.</b> A step-3 pane that read
     *                  complete after a single upload would show 100% behind a Submit that answers
     *                  400, which is the failure this whole contract exists to prevent.
     * @param consent   step 4 — {@code profile.md}: <i>"The professional declares the role they are
     *                  applying for <b>and</b> consents"</i>. So both halves: {@code consent} and
     *                  {@code authority}. The step keeps {@code profile.md}'s name for the step while
     *                  carrying all of the step's requirements, which is what § Gap Update asks for.
     */
    public record Steps(boolean account, boolean profile, boolean documents, boolean consent) implements Serializable {}
}
