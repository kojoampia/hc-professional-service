package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A push registration for one app installation.
 *
 * <p>{@code accountId} is the gateway login — the same value the JWT carries as {@code sub} and
 * the same equality {@code WebsocketConfiguration} relies on for STOMP routing. Keeping the two
 * transports keyed identically is what lets the client dedupe across them.
 *
 * <p>No message content is ever stored here, and nothing here is a credential: an FCM registration
 * token identifies a device to Google, it does not authorise anything against this service.
 */
@Document(collection = "device_token")
public class DeviceToken implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** FCM registration token. Unique — see {@code DeviceTokenIndexInitializer}. */
    @Field("token")
    private String token;

    /** Gateway login of whoever most recently signed in on this device. */
    @Field("account_id")
    private String accountId;

    /** {@code ANDROID} or {@code IOS}. */
    @Field("platform")
    private String platform;

    @Field("app_version")
    private String appVersion;

    /** Preferred language, so a server-rendered fallback body can be localised. */
    @Field("lang_key")
    private String langKey;

    @Field("created_date")
    private Instant createdDate;

    @Field("last_seen_at")
    private Instant lastSeenAt;

    /** Set when FCM reports the token dead; the row is kept for diagnosis rather than deleted. */
    @Field("disabled_at")
    private Instant disabledAt;

    @Field("disabled_reason")
    private String disabledReason;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public String getLangKey() {
        return langKey;
    }

    public void setLangKey(String langKey) {
        this.langKey = langKey;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Instant createdDate) {
        this.createdDate = createdDate;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }

    public void setDisabledAt(Instant disabledAt) {
        this.disabledAt = disabledAt;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    public void setDisabledReason(String disabledReason) {
        this.disabledReason = disabledReason;
    }

    public boolean isActive() {
        return disabledAt == null;
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "DeviceToken{" +
            "id='" + id + '\'' +
            ", accountId='" + accountId + '\'' +
            ", platform='" + platform + '\'' +
            ", disabledAt=" + disabledAt +
            '}';
    }
}
