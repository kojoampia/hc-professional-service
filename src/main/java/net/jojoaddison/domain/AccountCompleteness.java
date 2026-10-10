package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Whether onboarding <b>step 1</b> is satisfied for one account — the only thing in this service
 * that is a <em>projection of another service's state</em> rather than a fact this service owns
 * (backlog.md row 230, unit A).
 *
 * <h2>⭐ Why this is stored, when every other requirement is computed per request</h2>
 *
 * <p>Row 230 settles that {@code GET /api/onboarding/progress} keeps computing the meter from the
 * database and stays authoritative, so that a broker outage degrades to "no live refresh" rather
 * than "no meter" — {@code application.kafka.enabled=false} is a supported configuration and an
 * event-fed meter would otherwise sit at zero while the service reported healthy.
 *
 * <p><b>Steps 2, 3 and 4 satisfy that by reading {@code Profile}, {@code PersonalDocument} and
 * {@code ProfessionalApplication}, which are here. Step 1 cannot, because its four fields are on
 * {@code User} in {@code hcProfessionalGateway}</b> — {@code OnboardingService}'s own note refuses to
 * invent a cross-service call to read them, and {@code GatewayUserClient} could not serve this path
 * anyway: it reads {@code ROLE_ADMIN} endpoints relaying the caller's token, and an applicant holds
 * {@code ROLE_USER}. So "compute it from the database" and "do not call the gateway" together force
 * exactly one shape: <b>the answer has to be in this database before the request arrives.</b>
 *
 * <p>So this is not a departure from row 230's compute-don't-project rule — it is that rule applied
 * to the one step whose inputs are in another application. The alternative considered and rejected
 * was keeping the verdict in memory on the consumer: it needs no collection and no write, and it
 * reproduces the exact failure row 230 exists to avoid, because <b>every restart would silently
 * reset step 1 to outstanding</b> for every applicant until each of them next wrote their account.
 *
 * <h2>What stops it drifting</h2>
 *
 * <ul>
 *   <li><b>The account id is the {@code _id}</b>, so the collection physically cannot hold two
 *       answers for one account. There is no "find the latest row" query to get wrong.</li>
 *   <li><b>One writer</b> — {@code OnboardingService.recordAccountCompleteness}, called only by
 *       {@code MeterConsumer}. Nothing in {@code web.rest} can write it and no client can set it.</li>
 *   <li><b>{@link #getObservedAt} is the event's {@code occurredAt}, and an older one is refused.</b>
 *       At-least-once delivery is not at-least-once ordering, so the two frames either side of a
 *       correction can arrive in either order; without this a redelivered older frame would move the
 *       step back.</li>
 *   <li><b>Nothing is gated on it.</b> It feeds {@code OnboardingProgressDTO.Steps.account} and no
 *       other reader — not {@code complete}, not the {@code ACTIVE} gate, not the submit gate. So a
 *       wrong or absent value can only mis-display a tick; it can never refuse a clinician. See
 *       {@code OnboardingProgressDTO.Steps}, which argues that separation at length.</li>
 * </ul>
 *
 * <p>⚠ <b>Absent means "not known to be complete", not "known to be incomplete"</b>, and the two are
 * indistinguishable from here by design — the only field is the verdict. The practical consequence is
 * bounded and real: an account that has not been written since this shipped has no row, so step 1
 * reads {@code false} until it is next saved. No backfill exists, because a backfill would need the
 * cross-service read this whole design refuses.
 *
 * <p>⛔ <b>It is excluded from {@code EntityChangeAnnouncer}</b>, whose javadoc asks for exactly that
 * of anything a consumer persists. A row here is an internal projection, not a clinician's record,
 * and announcing it would put noise on {@code professional.event} — a channel hc-admin reads as an
 * audit trail of this subsystem's domain.
 */
@Document(collection = "account_completeness")
public class AccountCompleteness implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The gateway's {@code User.id} — what {@code Profile.accountId} and
     * {@code ProfessionalApplication.accountId} hold, and the estate's only join for a professional
     * (backlog.md item 50).
     *
     * <p><b>It is the {@code _id} rather than an indexed field, deliberately.</b> One row per account
     * then costs nothing to enforce and cannot be got wrong by a query — a second row for the same
     * clinician is not a thing this collection can contain.
     */
    @Id
    private String accountId;

    @Field("complete")
    private boolean complete;

    @Field("observed_at")
    private Instant observedAt;

    public String getAccountId() {
        return this.accountId;
    }

    public AccountCompleteness accountId(String accountId) {
        this.accountId = accountId;
        return this;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public boolean isComplete() {
        return this.complete;
    }

    public AccountCompleteness complete(boolean complete) {
        this.complete = complete;
        return this;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }

    /** When the gateway observed this verdict — the event's {@code occurredAt}, never the write time. */
    public Instant getObservedAt() {
        return this.observedAt;
    }

    public AccountCompleteness observedAt(Instant observedAt) {
        this.observedAt = observedAt;
        return this;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }

    @Override
    public String toString() {
        return "AccountCompleteness{accountId='" + accountId + "', complete=" + complete + ", observedAt=" + observedAt + "}";
    }
}
