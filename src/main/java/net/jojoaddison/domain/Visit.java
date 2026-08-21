package net.jojoaddison.domain;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.time.LocalTime;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * One customer visit inside a {@link DutyRoster} round (docs/duty-roster.md § 4, DR2).
 *
 * <p>A shift serves several customers, which is what a mobile health service actually does, so a
 * roster row is a <em>round</em> and this is one call within it. A round with no visits is still
 * valid — ward cover, on call, administrative time — and shows in the day view by its shift window
 * alone.
 *
 * <p><b>Times are clock times, and the date they fall on comes from the round.</b> {@code startTime}
 * and {@code endTime} are bare {@link LocalTime}s; which calendar day each lands on is derived from
 * the roster's {@code date} and its {@code ShiftType} window. That matters for {@code NIGHT}, which
 * runs 23:00 to 07:00 the next morning: a 01:00 visit belongs to the <em>previous</em> date's shift.
 * {@code DutyRosterService} owns that resolution and every consumer should go through it rather than
 * pairing the time with the round's date directly.
 *
 * <p><b>The snapshot fields are personal data and this is the first time any has lived in
 * professionalservice.</b> {@code customerName}, {@code customerAddress} and {@code customerPhone}
 * are copied from the patient stack when the round is built, so a clinician standing at a door still
 * has an address when the sibling stack is down. They are cleared 90 days after the visit while
 * {@code customerId} is kept, so history and audit survive and the personal data does not linger
 * here or in the nightly backups.
 *
 * <p>They must not travel any further. Domain-event payloads carry identifiers only
 * (professional-onboarding-workflow.md § Domain events), and a snapshot must never reach a Kafka
 * envelope, a log line or an OpenTelemetry span attribute — the agent is baked into the image and
 * attributes are easy to add without thinking. {@link DutyRoster#toString()} deliberately omits the
 * visits for exactly this reason, and {@code DutyRosterVisitPrivacyIT} holds that line.
 */
public class Visit implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Identity within the round, so a single visit can be moved (DR4).
     *
     * <p>An embedded document needs none of its own to be stored, and this one had none until
     * visit-level reassignment required a way to name one. Assigned on write when absent; never
     * reused. It is <b>not</b> a customer identifier and carries no meaning — two visits to the same
     * person on the same day have different ids.
     */
    @Field("id")
    private String id;

    /**
     * The patient stack's {@code Profile.patientId} — <b>not</b> its profile id.
     *
     * <p>One id serves both reads the day view needs: {@code patientservice} {@code Profile} for
     * demographics and address, and {@code ActivityLogEntry} for the trail, which is already keyed on
     * {@code patientId}. There is no {@code Patient} resource over there to point at; patients are
     * {@code Profile} documents joined on this field.
     */
    @NotNull
    @Field("customer_id")
    private String customerId;

    @NotNull
    @Field("start_time")
    private LocalTime startTime;

    @NotNull
    @Field("end_time")
    private LocalTime endTime;

    /** Snapshot, see the class javadoc. Null once purged, or if the patient stack was unreachable. */
    @Field("customer_name")
    private String customerName;

    /** Snapshot. One formatted line, digital address first — see {@code PatientServiceDtos.Address}. */
    @Field("customer_address")
    private String customerAddress;

    /** Snapshot. The mobile where there is one; a clinician calls ahead from the street. */
    @Field("customer_phone")
    private String customerPhone;

    public String getId() {
        return this.id;
    }

    public Visit id(String id) {
        this.id = id;
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCustomerId() {
        return this.customerId;
    }

    public Visit customerId(String customerId) {
        this.customerId = customerId;
        return this;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public LocalTime getStartTime() {
        return this.startTime;
    }

    public Visit startTime(LocalTime startTime) {
        this.startTime = startTime;
        return this;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return this.endTime;
    }

    public Visit endTime(LocalTime endTime) {
        this.endTime = endTime;
        return this;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public String getCustomerName() {
        return this.customerName;
    }

    public Visit customerName(String customerName) {
        this.customerName = customerName;
        return this;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerAddress() {
        return this.customerAddress;
    }

    public Visit customerAddress(String customerAddress) {
        this.customerAddress = customerAddress;
        return this;
    }

    public void setCustomerAddress(String customerAddress) {
        this.customerAddress = customerAddress;
    }

    public String getCustomerPhone() {
        return this.customerPhone;
    }

    public Visit customerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
        return this;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    /** Clears the three snapshot fields, keeping {@code customerId}. Returns true if anything changed. */
    public boolean clearSnapshot() {
        boolean had = customerName != null || customerAddress != null || customerPhone != null;
        this.customerName = null;
        this.customerAddress = null;
        this.customerPhone = null;
        return had;
    }

    /**
     * Identifiers and times only — <b>never</b> the snapshot.
     *
     * <p>This is not decoration. {@code DutyRosterResource} logs at debug on the write path, and a
     * {@code toString} that included the snapshot would put patient names, addresses and phone
     * numbers into the log file of a service that is not supposed to hold them at all.
     */
    // prettier-ignore
    @Override
    public String toString() {
        return "Visit{" +
                "id=" + getId() +
                ", customerId='" + getCustomerId() + "'" +
                ", startTime='" + getStartTime() + "'" +
                ", endTime='" + getEndTime() + "'" +
                "}";
    }
}
