package net.jojoaddison.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.PatientDirectoryService.RestrictedPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * What a degraded directory read leaves behind in production (backlog item 116).
 *
 * <p><b>The defect this closes is that losing a column and losing patients looked identical.</b>
 * {@link RestrictedPart} argues the asymmetry in its own javadoc — {@code LAST_ACTIVITY} blanks a
 * column on rows that are all present, {@code CASE_ASSIGNMENTS} means rows are <em>missing</em> — and
 * until this class the logging did not follow it: both branches of
 * {@link PatientDirectoryService#directory} were {@code log.debug}, and {@code application-prod.yml}
 * sets {@code net.jojoaddison: INFO}, so <b>neither line was emitted in production at all</b>. The
 * only production trace of a directory served short of rows was
 * {@code PatientServiceClient.failedRead}'s INFO line, which names the <em>path</em> and not the
 * caller.
 *
 * <h3>What is being watched for, because it is not the pharmacist</h3>
 * For {@code pharmacist}, {@code chemist} and {@code technician} a refusal is hc-patient's
 * scope-of-practice matrix working exactly as designed, and silence about it is correct — that is why
 * item 107 moved a refusal off ERROR in the first place. <b>The failure mode is drift in a matrix this
 * stack does not own.</b> If hc-patient ever stops admitting {@code ROLE_DOCTOR} to
 * {@code /api/clinical-cases}, every doctor's directory silently loses every case-only patient: the
 * response is 200, {@code X-Restricted-Parts} is unread by both clients until item 114, and nothing
 * else says so. The signal that distinguishes that from the pharmacist is <b>which discipline</b>, so
 * that is what is instrumented.
 *
 * <h3>Two signals, and each is answering a different question</h3>
 * <ul>
 *   <li><b>{@link #METER_NAME}, tagged {@link #PART_TAG} and {@link #DISCIPLINE_TAG}</b> — the
 *       primary signal, and the one an alert should be written against. Both parts are counted, not
 *       only the row-dropping one: two things cannot be told apart by instrumenting one of them, and
 *       with only {@code caseAssignments} counted "no series" would mean both <em>no refusals</em> and
 *       <em>refusals, but only of the column kind</em>.
 *   <li><b>A WARN, once per {@code (part, discipline)} per process, for the row-dropping part
 *       only</b> — so that the fact is in the log a support engineer is already reading, at a level
 *       {@code net.jojoaddison: INFO} actually emits, without the per-request volume item 107 removed.
 * </ul>
 *
 * <h3>The counters are registered lazily, and that is load-bearing</h3>
 * A Micrometer counter that has never been incremented <b>produces no series at all</b> through this
 * estate's OTLP push — measured for {@code security_authentication_invalid_tokens_errors_total}, which
 * the actuator serves at {@code 0.0} and Mimir does not hold (backlog item 97). Pre-registering the
 * twenty possible combinations the way {@code SecurityMetersService} pre-registers its five would
 * therefore destroy the cheapest alert available here: <em>a series appearing that was not there
 * before</em>. {@code patient_directory_restricted_reads_total{part="caseAssignments",
 * discipline="doctor"}} existing at all is the drift above. So each counter is created on the first
 * refusal that needs it and no sooner.
 *
 * <h3>What this deliberately does not tell an operator</h3>
 * <ul>
 *   <li><b>Not which clinician</b>, beyond the one login in the once-per-process line. A profile id or
 *       a login as a meter <em>tag</em> is unbounded cardinality on a per-caller dimension, and
 *       nothing here is worth that.
 *   <li><b>Not how many clinicians.</b> The counter counts <em>reads</em>, so one clinician refreshing
 *       ten times and ten clinicians refreshing once are the same number.
 *   <li><b>Not whether it is wrong.</b> This stack does not own the scope-of-practice matrix, so
 *       nothing here can say that a given {@code (part, discipline)} pair should not be happening —
 *       see {@link #disciplineOfCaller()} for why encoding that judgement was rejected.
 *   <li><b>Not how many patients were lost.</b> The refusal is of the case collection; how many
 *       case-only patients it cost is not knowable from a read that did not happen.
 * </ul>
 */
@Service
public class PatientDirectoryRestrictionMeters {

    private static final Logger LOG = LoggerFactory.getLogger(PatientDirectoryRestrictionMeters.class);

    /**
     * The meter name, and it is a contract: a dashboard or an alert names this string.
     *
     * <p>Micrometer renders it with the base unit appended, so what reaches Prometheus and Mimir is
     * {@code patient_directory_restricted_reads_total}.
     *
     * <p><b>It reaches Mimir only while this service has the agent's Micrometer bridge.</b> Until
     * 2026-09-14 {@code OTEL_INSTRUMENTATION_MICROMETER_ENABLED} was set on the gateway alone, and this
     * service exported the agent's own instrumentation and <em>no Micrometer meter at all</em> —
     * measured with a control, {@code application_ready_time_seconds} present for the gateway and
     * absent here while {@code jvm_class_count} was present for both. Turning it off again silently
     * removes every claim below about a series appearing. Note also that item 97's <em>a
     * never-incremented counter yields no series</em> was measured on the <em>gateway</em>; the lazy
     * registration below is right for that reason but the measurement behind it is borrowed, and
     * remains unverified on this service until a refused read here is seen in Mimir.
     *
     * <p><b>Unprefixed by the service, in line with {@code SecurityMetersService} beside it</b> — the
     * product is a resource attribute ({@code service_name}) rather than part of the name. Worth
     * knowing that hc-admin has a screen it also calls a "patient directory", and that backlog item 97
     * is what happens when two products converge on one metric name with different tag vocabularies:
     * if that one is ever instrumented, it must not land here with a different shape.
     */
    public static final String METER_NAME = "patient.directory.restricted";

    /** Appended to the rendered name, as {@code SecurityMetersService} does with {@code errors}. */
    public static final String METER_BASE_UNIT = "reads";

    public static final String METER_DESCRIPTION =
        "Patient directory reads served without a composed part the caller's discipline may not read.";

    /**
     * Which part was withheld — {@link RestrictedPart#token()}, the same string
     * {@code X-Restricted-Parts} carries, so a panel and a response agree on the vocabulary.
     */
    public static final String PART_TAG = "part";

    /** The caller's discipline; see {@link #disciplineOfCaller()} for the closed set it comes from. */
    public static final String DISCIPLINE_TAG = "discipline";

    /**
     * The discipline tag for a caller holding none of the nine known authorities — an applicant with
     * {@code ROLE_USER} alone, or a token from a sibling stack. A value rather than a missing tag,
     * because a series missing a dimension the rest carry is the shape that breaks a {@code by
     * (discipline)} aggregation silently.
     */
    public static final String NO_DISCIPLINE = "none";

    private static final String ROLE_PREFIX = "ROLE_";

    private final MeterRegistry registry;

    /**
     * The {@code (part, discipline)} pairs this process has already announced.
     *
     * <p><b>Bounded by construction, which is why the key is the discipline and not the caller.</b>
     * Both dimensions come from closed sets — two parts, ten disciplines — so this set can never hold
     * more than twenty strings however many clinicians sign in. Keying it on a profile id would make
     * it grow with the workforce <em>and</em> produce one WARN per clinician, which is the per-caller
     * volume item 107 removed, slowed down rather than avoided.
     *
     * <p><b>On restart it is empty again, deliberately.</b> Each pair is re-announced once on the
     * first directory read that needs it, so the line is present in the log of whichever container is
     * actually being read rather than only in one retired weeks ago — and a deploy restates the
     * current steady state rather than hiding it. The cost is on the record: this is not a signal that
     * re-fires while drift persists, which is why the meter and not the log is what an alert watches.
     */
    private final Set<String> announced = ConcurrentHashMap.newKeySet();

    public PatientDirectoryRestrictionMeters(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Records the parts one directory read was served without.
     *
     * <p><b>Called once, from where the {@link PatientDirectoryService.Directory} is built</b>, rather
     * than from the two degrade branches. The set recorded here is the set rendered into
     * {@code X-Restricted-Parts}, so the metric and the header cannot come to disagree — two call
     * sites recording what they each believed was withheld is exactly how they would.
     *
     * <p>Empty is the ordinary case — five of the eight disciplines, on every request — and costs a
     * branch. Nothing is registered, nothing is resolved, no tag is built.
     */
    public void record(Set<RestrictedPart> restrictions) {
        if (restrictions.isEmpty()) {
            return;
        }
        String discipline = disciplineOfCaller();
        for (RestrictedPart part : restrictions) {
            Counter.builder(METER_NAME)
                .baseUnit(METER_BASE_UNIT)
                .description(METER_DESCRIPTION)
                .tag(PART_TAG, part.token())
                .tag(DISCIPLINE_TAG, discipline)
                .register(registry)
                .increment();
            if (part.removesRows()) {
                announceOnce(part, discipline);
            }
        }
    }

    /**
     * One WARN the first time a discipline loses rows in this process, and silence thereafter.
     *
     * <p><b>The row-dropping part only</b>, following {@link RestrictedPart#removesRows()} rather than
     * naming a constant here: the enum is where the asymmetry is argued, and a third part added later
     * has to answer the question there rather than silently inherit an answer from this file.
     *
     * <p>The <b>login</b> is the handle, not the profile id the backlog row suggested. It is what this
     * repository already writes down for a person to read — {@code createdBy}, {@code lastModifiedBy},
     * every event {@code actor} — while the account id is for comparing against stored data; and it
     * costs no repository read on a path that is already short one upstream collection. It names the
     * <em>first</em> caller to reach this state in this process and not the only one, which the line
     * says so nobody reads it as a complete list.
     */
    private void announceOnce(RestrictedPart part, String discipline) {
        if (!announced.add(part.token() + ':' + discipline)) {
            return;
        }
        LOG.warn(
            // Generic over the part rather than describing the case half, although that is the only
            // part that reaches here today: a message naming a route a future part does not use is a
            // claim nothing established, which is the failure mode this whole area keeps repeating.
            //
            // Trimmed on review: this lands in a Grafana log panel, where the reasoning is a wall and
            // the four facts are what a reader needs. The reasoning is in this class's javadoc.
            "The patient directory is being served to discipline '{}' WITHOUT the '{}' part: PATIENTS are missing from the list, " +
            "not a column blanked on the patients in it. Expected for a discipline hc-patient's scope-of-practice matrix does not " +
            "admit, NOT expected for one it does. First seen for login '{}'; once per part per discipline per process, so count " +
            "patient_directory_restricted_reads_total rather than these lines",
            discipline,
            part.token(),
            SecurityUtils.getCurrentUserLogin().orElse("unknown")
        );
    }

    /**
     * The calling clinician's discipline, as a low-cardinality tag value.
     *
     * <p><b>Derived from {@link AuthoritiesConstants#CLINICAL_AND_ADMIN}, never from the token.</b>
     * That array is already this service's canonical "the administrator and the eight clinical
     * disciplines", so the tag cannot drift from it; and taking the value from the caller's own
     * authority strings would put an unbounded, caller-controlled dimension into a meter — a token
     * carrying {@code ROLE_ANYTHING} would mint a series for it.
     *
     * <p><b>A caller holding two is reported under the first of {@link #DISCIPLINES_BEFORE_ADMIN},
     * which is deliberately not that array's own order.</b> Deterministic, and a report of one read
     * rather than a statement about a capability — the same caveat {@link RestrictedPart}'s javadoc
     * makes about the restriction set itself.
     *
     * <p><b>What is deliberately not decided here: whether this discipline <em>should</em> have been
     * refused.</b> Escalating only the unexpected pairs would need a list of which disciplines
     * hc-patient's matrix admits to which collection — a second copy of a matrix owned by another
     * product, in the one stack least able to notice it had gone stale, which is precisely the class
     * of drift this instrumentation exists to catch. So every row-dropping refusal is reported
     * identically and the operator compares it against what the matrix is supposed to say.
     */
    static String disciplineOfCaller() {
        for (String authority : DISCIPLINES_BEFORE_ADMIN) {
            if (SecurityUtils.hasCurrentUserThisAuthority(authority)) {
                return (authority.startsWith(ROLE_PREFIX) ? authority.substring(ROLE_PREFIX.length()) : authority).toLowerCase(Locale.ROOT);
            }
        }
        return NO_DISCIPLINE;
    }

    /**
     * {@link AuthoritiesConstants#CLINICAL_AND_ADMIN} with {@code ROLE_ADMIN} moved to the end — the
     * order {@link #disciplineOfCaller()} resolves a multi-authority caller in.
     *
     * <p><b>The array's own order shadows the exact signal this class exists to raise, and that is a
     * measurement rather than a worry.</b> It is declared {@code ADMIN, DOCTOR, NURSE, PARAMEDIC, …}
     * and the resolver returns on first match, so an account holding {@code ROLE_ADMIN} <em>and</em>
     * {@code ROLE_DOCTOR} — a clinical lead, or one of the quality stack's seeded accounts — loading
     * the directory while hc-patient's matrix refuses doctors would mint
     * {@code discipline="admin"}. Two things go wrong with that and the second is worse. The drift is
     * <b>misattributed</b>, so an operator reads it and goes looking at administrator entitlement
     * rather than at the doctor matrix. And if {@code admin} has already appeared for a reason of its
     * own, the doctor's drift produces <b>no new label value at all</b> — which is precisely the
     * condition an alert on this meter keys on, since a never-incremented counter has no series and
     * "a series that was not there before" is the whole signal.
     *
     * <p><b>Derived rather than restated, and it moves exactly one name.</b> A hand-written list of
     * the eight disciplines would be a fourth copy of a set this estate already keeps in three repos
     * and watches for drift; this filters the canonical array and appends the one member of it that
     * is not a discipline, so a ninth discipline added there is picked up here with no edit.
     * {@code admin} stays in the list rather than being dropped: an administrator with no clinical
     * authority is still somebody whose directory was degraded, and {@code NO_DISCIPLINE} would say
     * something false about them.
     */
    private static final List<String> DISCIPLINES_BEFORE_ADMIN = Stream.concat(
        Arrays.stream(AuthoritiesConstants.CLINICAL_AND_ADMIN).filter(authority -> !AuthoritiesConstants.ADMIN.equals(authority)),
        Stream.of(AuthoritiesConstants.ADMIN)
    ).toList();
}
