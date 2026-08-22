package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Proof that one client-supplied write has already been forwarded to patientservice.
 *
 * <p><b>Why this exists here and not there.</b> The records themselves live in patientservice, so a
 * unique index on the created document is not this service's to add. What this service <em>can</em>
 * guarantee is that it will not forward the same {@code clientRef} twice — which is precisely the
 * guarantee an offline write queue needs. A phone that files a clinical note, loses signal before
 * the response arrives, and retries on reconnect must not end up with two notes in the record; a
 * duplicated observation is the kind of error a clinician does not forgive, and unlike a lost one it
 * is invisible until someone reads the record back.
 *
 * <p><b>What it is not.</b> It is not a cache and not an audit log. It stores the ids and nothing
 * about the content — no summary, no detail, no patient-identifying text beyond the patient id the
 * caller was already entitled to write against.
 *
 * <p>{@code clientRef} is unique across the collection rather than per account. A key is generated
 * client-side and is expected to be a UUID; scoping it per account would let two accounts collide on
 * a guessable key, and the account is checked on replay anyway.
 */
@Document(collection = "patient_write_receipt")
public class PatientWriteReceipt implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** The client's idempotency key. Unique — see {@code PatientWriteReceiptIndexInitializer}. */
    @Field("client_ref")
    private String clientRef;

    /** Gateway login of the clinician who filed it, checked on replay. */
    @Field("account_id")
    private String accountId;

    @Field("patient_id")
    private String patientId;

    /** {@code activity} or {@code report}. A key replayed against the other kind is a client fault. */
    @Field("kind")
    private String kind;

    /** The id patientservice assigned, so a replay can answer with the original rather than a copy. */
    @Field("created_id")
    private String createdId;

    @Field("created_at")
    private Instant createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getClientRef() {
        return clientRef;
    }

    public void setClientRef(String clientRef) {
        this.clientRef = clientRef;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getCreatedId() {
        return createdId;
    }

    public void setCreatedId(String createdId) {
        this.createdId = createdId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
