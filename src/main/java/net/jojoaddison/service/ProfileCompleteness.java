package net.jojoaddison.service;

import java.util.ArrayList;
import java.util.List;
import net.jojoaddison.domain.Address;
import net.jojoaddison.domain.EmergencyContact;
import net.jojoaddison.domain.Profile;

/**
 * Whether every field {@code profile.md}'s step 2 requires is provided — the predicate behind
 * {@code Profile.status = PROFILE_COMPLETED} (F1).
 *
 * <h2>What the specification says, and that it had no implementation at all</h2>
 *
 * <p>{@code profile.md} § "Step 2 — Create the profile" says <i>"Every field in the Profile model is
 * required"</i> and, under the endpoint, <i>"Set {@code profile.status} to {@code PROFILE_COMPLETED}
 * when every field is provided"</i>. <b>Nothing wrote {@code Profile.status} anywhere in
 * {@code src/main}</b> before this class: a search for {@code PROFILE_COMPLETED} found the enum
 * declaration and four {@code OnboardingService} sites, every one of them operating on
 * {@code ProfessionalApplication.status}, which is a different field on a different document
 * recording a different thing. {@code Profile.setStatus} had no caller.
 *
 * <h2>⭐ "Every field" is the literal, nested reading — 39 values, decided by the owner</h2>
 *
 * <table>
 *   <caption>The required set</caption>
 *   <tr><th>group</th><th>fields</th><th>count</th></tr>
 *   <tr><td>{@code Profile}</td>
 *       <td>{@code firstName}, {@code middleNames}, {@code lastName}, {@code birthDate},
 *           {@code sex}, {@code mobilePhone}, {@code phoneNumber}, {@code email},
 *           {@code cardType}, {@code cardNumber}</td><td>10</td></tr>
 *   <tr><td>{@code Profile.address}</td>
 *       <td>{@code digitalAddress}, {@code streetAddress}, {@code town}, {@code city},
 *           {@code district}, {@code region}, {@code country}</td><td>7</td></tr>
 *   <tr><td><b>each</b> contact, <b>minimum two</b></td>
 *       <td>{@code name}, {@code relationship}, {@code email}, {@code phone}, plus a full nested
 *           {@link Address} (the same 7)</td><td>11 each</td></tr>
 * </table>
 *
 * <p><b>Excluded, deliberately:</b> {@code id} and {@code accountId} (neither is a user input —
 * {@code accountId} is {@code READ_ONLY} over HTTP and forced from the token), {@code status}
 * itself (this predicate is what decides it; requiring it would be circular), and <b>{@code title}
 * — which is not in {@code profile.md}'s Profile model table</b> even though the page header
 * renders {@code profile.title}. {@code Address.id} is excluded for the same reason as
 * {@code Profile.id}, and the four {@code Address} audit fields because nothing fills them.
 * {@code areaCode} and {@code state} are absent from this list because F10 removed them from
 * {@link Address} outright.
 *
 * <h2>⚠ The consequence for rows already stored is accepted, not worked around</h2>
 *
 * <p><b>No profile now in the quality or production database reaches {@code PROFILE_COMPLETED}
 * until it is re-saved through the wizard.</b> The server's older advisory predicates
 * ({@code OnboardingService.personalDetailsComplete} and its two neighbours) checked 14 values
 * between them and never looked at {@code middleNames}, {@code phoneNumber}, {@code digitalAddress},
 * {@code town}, {@code district}, a contact's {@code email}, or a contact's address at all. ⛔ Do not
 * soften this predicate to protect existing rows: refusing the specification to avoid disturbing
 * stored data is exactly the reasoning the owner overruled when F1, F2 and F5 were filed.
 *
 * <h2>Which definition of "complete" is authoritative, since there are now two</h2>
 *
 * <p><b>This one, and only for {@code Profile.status}.</b> The three predicates in
 * {@code OnboardingService} feed {@code GET /api/onboarding/progress} — the eight-requirement
 * progress meter — and they stay where they are and keep that job, because folding the meter onto
 * {@code profile.md}'s four steps is T5's task and not this change's. They are <em>narrower</em>
 * than this predicate on every field and <em>equal</em> to it on none, so the two cannot contradict
 * each other in the direction that matters: anything this class calls complete, those three call
 * complete too. ⚠ The reverse does not hold, so a profile can read 100% on the progress meter and
 * carry no {@code status} — that is the known gap T5 closes, and it is recorded here rather than
 * papered over.
 *
 * <p>In {@code ..service..} rather than {@code ..web.rest..} because {@code TechnicalStructureTest}
 * lets a service reference {@code ..domain..} and this predicate is about the document, not about
 * HTTP. Static and stateless: the answer is a function of the profile and of nothing else, so
 * neither write path needs an instance to ask.
 */
public final class ProfileCompleteness {

    /** {@code profile.md} step 2: <i>"At least <b>two</b> emergency contacts are required."</i> */
    public static final int REQUIRED_CONTACTS = 2;

    /**
     * The three requirement keys this predicate can report, and the <b>single</b> spelling of each.
     *
     * <p>They are declared here and referenced by {@code OnboardingService}'s progress meter rather
     * than spelled twice, because the client maps every key to a translated label in four languages:
     * a key that differs between the meter and a refusal renders as the key itself, mid-screen, with
     * nothing thrown and nothing logged. {@code OnboardingService} held private copies of these three
     * strings until F-B; two correct-for-now copies is how this estate arrives at one wrong one.
     */
    public static final String REQ_PROFILE = "profile";

    /** @see #REQ_PROFILE */
    public static final String REQ_ADDRESS = "address";

    /** @see #REQ_PROFILE */
    public static final String REQ_NEXT_OF_KIN = "nextOfKin";

    private ProfileCompleteness() {}

    /**
     * Whether this profile provides all 39 values — see the table on the class.
     *
     * @param profile the stored row after the write has been applied, or {@code null}.
     * @return {@code true} when every required value is present.
     */
    public static boolean isComplete(Profile profile) {
        return missingRequirements(profile).isEmpty();
    }

    /**
     * Which of the three groups this profile does not yet provide, in the order the page shows them
     * — empty when {@link #isComplete} (F-B).
     *
     * <h2>Why a list of keys rather than the boolean the class opened with</h2>
     *
     * <p>{@code profile.md} § Gap Update conditions step 4's submit on <i>"when all requirements are
     * satisfied"</i>, and the only check on that path was step 3's four documents: an applicant with
     * every document and a blank {@code phoneNumber} got <b>200</b>, reached
     * {@code CREDENTIAL_REVIEW}, and had {@code onboarding.state COMPLETED} published — then
     * activation failed with {@code ACTIVATION_REQUIRES_COMPLETE_PROFILE} <em>after a reviewer had
     * done the work</em>, with nothing having told the applicant their profile was short.
     *
     * <p><b>So the applicant has to learn which requirement is unsatisfied, and a boolean cannot say
     * it.</b> {@code OnboardingService.requireCompleteProfile} already names its unsatisfied keys for
     * exactly this reason; this is the same courtesy on the earlier gate, and the reason
     * {@link #isComplete} is now derived from this method rather than the other way round — one
     * traversal, one answer, no second definition that could disagree about a field.
     *
     * <p>⚠ <b>Three keys, not 39.</b> The grouping is {@code profile.md}'s own — the Profile model,
     * the Address model, the EmergencyContact list — and it is what the client has labels for. Which
     * of the eleven values inside a contact is missing is the form's to show, not the refusal's.
     *
     * @param profile the stored row after the write has been applied, or {@code null} — in which case
     *     all three are missing, which is the honest answer for an applicant with no profile at all.
     */
    public static List<String> missingRequirements(Profile profile) {
        if (profile == null) {
            return List.of(REQ_PROFILE, REQ_ADDRESS, REQ_NEXT_OF_KIN);
        }
        List<String> missing = new ArrayList<>();
        if (!personalDetailsProvided(profile)) {
            missing.add(REQ_PROFILE);
        }
        if (!addressProvided(profile.getAddress())) {
            missing.add(REQ_ADDRESS);
        }
        if (!contactsProvided(profile.getContacts())) {
            missing.add(REQ_NEXT_OF_KIN);
        }
        return List.copyOf(missing);
    }

    /** The ten fields of {@code Profile} itself. {@code sex} and {@code cardType} are enums (F9). */
    private static boolean personalDetailsProvided(Profile profile) {
        return (
            hasText(profile.getFirstName()) &&
            hasText(profile.getMiddleNames()) &&
            hasText(profile.getLastName()) &&
            profile.getBirthDate() != null &&
            profile.getSex() != null &&
            hasText(profile.getMobilePhone()) &&
            hasText(profile.getPhoneNumber()) &&
            hasText(profile.getEmail()) &&
            profile.getCardType() != null &&
            hasText(profile.getCardNumber())
        );
    }

    /**
     * The seven input fields of an {@link Address} — the eight of {@code profile.md}'s model less
     * {@code id}, which is not a user input.
     */
    private static boolean addressProvided(Address address) {
        return (
            address != null &&
            hasText(address.getDigitalAddress()) &&
            hasText(address.getStreetAddress()) &&
            hasText(address.getTown()) &&
            hasText(address.getCity()) &&
            hasText(address.getDistrict()) &&
            hasText(address.getRegion()) &&
            hasText(address.getCountry())
        );
    }

    /**
     * At least {@link #REQUIRED_CONTACTS} contacts, <b>every one of them complete</b>.
     *
     * <p>Not "at least two complete ones among however many": the specification requires every field
     * of the model and a contact is part of the model, so a third contact half typed in leaves the
     * profile incomplete. ⚠ That is a different rule from
     * {@code OnboardingService.nextOfKinComplete}, which counts the complete ones — and the
     * difference is deliberate, because a progress meter that goes <em>down</em> when a clinician
     * starts adding a contact punishes the behaviour the specification asks for, while a
     * {@code status} that said "complete" over a half-typed contact would be false.
     */
    private static boolean contactsProvided(List<EmergencyContact> contacts) {
        return contacts != null && contacts.size() >= REQUIRED_CONTACTS && contacts.stream().allMatch(ProfileCompleteness::contactProvided);
    }

    /** One contact: its four fields plus a full nested address — eleven values. */
    private static boolean contactProvided(EmergencyContact contact) {
        return (
            contact != null &&
            hasText(contact.getName()) &&
            hasText(contact.getRelationship()) &&
            hasText(contact.getEmail()) &&
            hasText(contact.getPhone()) &&
            addressProvided(contact.getAddress())
        );
    }

    /**
     * Provided means more than non-null: a blank string is what an untouched input posts, and
     * counting it would make the whole predicate satisfiable by submitting an empty form.
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
