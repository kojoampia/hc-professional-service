package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.broker.EntityChangeAction;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.Profile;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.mapping.event.AfterDeleteEvent;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The listener half of {@code professional.event} — backlog.md item 141.
 *
 * <p>{@link EntityChangeAnnouncerTest} covers what the announcer decides;
 * {@code net.jojoaddison.broker.EntityChangeEventTest} covers what the resulting frame carries; and
 * {@code EntityChangeOnEveryWriteIT} covers the one thing neither can, which is that Spring registers
 * this as a Mongo event listener at all.
 *
 * <p><b>The actor cases are the ones worth reading.</b> This subsystem has put a login on a wire
 * under a name the contract called an account identifier once already, and the reason it survived is
 * that nothing failed: {@code ProfileStatus.lastModifiedBy} is filled by
 * {@code SpringSecurityAuditorAware}, which reads the JWT subject. So the fixture here deliberately
 * gives the subject and the {@code uid} claim <b>different values</b> — a test whose token carried
 * the same string in both would pass against either implementation and prove nothing.
 */
class EntityChangeAnnouncerTest {

    private static final String LOGIN = "jdoe";
    private static final String ACCOUNT_ID = "user-42";

    private DomainEventPublisher publisher;
    private EntityChangeAnnouncer announcer;

    @BeforeEach
    void setUp() {
        publisher = mock(DomainEventPublisher.class);
        announcer = new EntityChangeAnnouncer(publisher);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * An insert is told from a replacement by the id being absent at conversion, which is the only
     * point it is absent — the converter assigns one before the save event is raised.
     */
    @Test
    void aFirstWriteIsAnnouncedAsCreated() {
        Profile profile = new Profile();

        announcer.onBeforeConvert(new BeforeConvertEvent<>(profile, "profile"));
        profile.setId("profile-7");
        announcer.onAfterSave(new AfterSaveEvent<>(profile, new Document(), "profile"));

        verify(publisher).publishEntityChange(eq("Profile"), eq("profile-7"), eq(EntityChangeAction.CREATED), any(), any(Instant.class));
    }

    @Test
    void aWriteOverAnExistingRowIsAnnouncedAsUpdated() {
        Profile profile = new Profile();
        profile.setId("profile-7");

        announcer.onBeforeConvert(new BeforeConvertEvent<>(profile, "profile"));
        announcer.onAfterSave(new AfterSaveEvent<>(profile, new Document(), "profile"));

        verify(publisher).publishEntityChange(eq("Profile"), eq("profile-7"), eq(EntityChangeAction.UPDATED), any(), any(Instant.class));
    }

    /**
     * ⚠ A delete produces a frame too, and it is the half an implementation built around save
     * callbacks silently omits — Spring Data has {@code AfterSaveCallback} but no delete callback, so
     * an author reaching for the entity-callback API rather than the event API gets creates and
     * updates and no deletions at all, with nothing failing.
     */
    @Test
    void aDeleteProducesAFrameToo() {
        Document query = new Document("_id", "doc-9");

        announcer.onAfterDelete(deleteEvent(query, PersonalDocument.class, "personal_document"));

        verify(publisher).publishEntityChange(
            eq("PersonalDocument"),
            eq("doc-9"),
            eq(EntityChangeAction.DELETED),
            any(),
            any(Instant.class)
        );
    }

    /**
     * The mapped query carries an {@link ObjectId} for every document Mongo generated an id for, not a
     * String. Matching only String makes every such delete look like a query naming no single id —
     * which is the afternoon {@code ProfileStatusAnnouncer} records having lost to it.
     */
    @Test
    void aDeleteKeyedByObjectIdIsAnnouncedRatherThanSkipped() {
        ObjectId id = new ObjectId();

        announcer.onAfterDelete(deleteEvent(new Document("_id", id), PersonalDocument.class, "personal_document"));

        verify(publisher).publishEntityChange(
            eq("PersonalDocument"),
            eq(id.toString()),
            eq(EntityChangeAction.DELETED),
            any(),
            any(Instant.class)
        );
    }

    /** A criteria delete matched an unknown set of rows; inventing ids for them would be worse. */
    @Test
    void aDeleteNamingNoSingleIdIsSkipped() {
        announcer.onAfterDelete(deleteEvent(new Document("profileId", "profile-7"), PersonalDocument.class, "personal_document"));

        verify(publisher, never()).publishEntityChange(anyString(), anyString(), any(), any(), any());
    }

    /**
     * ⛔ The actor is the {@code uid} claim, never the login.
     *
     * <p>The fixture's subject and {@code uid} differ on purpose; see the class comment. A regression
     * to {@code getCurrentUserLogin()} — or to {@code SpringSecurityAuditorAware}, which is what fills
     * the field this subsystem already got wrong — puts {@code "jdoe"} here and fails.
     */
    @Test
    void theActorIsTheAccountIdAndNeverTheLogin() {
        authenticateAsGatewayUser();
        Profile profile = new Profile();
        profile.setId("profile-7");

        announcer.onAfterSave(new AfterSaveEvent<>(profile, new Document(), "profile"));

        ArgumentCaptor<String> actor = ArgumentCaptor.forClass(String.class);
        verify(publisher).publishEntityChange(anyString(), anyString(), any(), actor.capture(), any(Instant.class));
        assertThat(actor.getValue()).isEqualTo(ACCOUNT_ID).isNotEqualTo(LOGIN);
    }

    /**
     * A write with no security context behind it — a scheduler, a startup runner, a Kafka consumer, a
     * migration — carries no actor rather than a placeholder in the account-id space.
     */
    @Test
    void aWriteWithNoCallerCarriesNoActor() {
        Profile profile = new Profile();
        profile.setId("profile-7");

        announcer.onAfterSave(new AfterSaveEvent<>(profile, new Document(), "profile"));

        verify(publisher).publishEntityChange(anyString(), anyString(), any(), eq(null), any(Instant.class));
    }

    /**
     * A {@code uid} minted by hc-admin or hc-patient names a row in <em>their</em> user store. The
     * three gateways share one signing key, so such a token authenticates here; its account id means
     * nothing in this database and is discarded rather than published as though it did.
     */
    @Test
    void aForeignIssuersAccountIdIsNotPublishedAsOurs() {
        authenticate(
            Jwt.withTokenValue("t").header("alg", "HS512").subject(LOGIN).claim("iss", "hc-admin-gateway").claim("uid", "their-user-99")
        );
        Profile profile = new Profile();
        profile.setId("profile-7");

        announcer.onAfterSave(new AfterSaveEvent<>(profile, new Document(), "profile"));

        verify(publisher).publishEntityChange(anyString(), anyString(), any(), eq(null), any(Instant.class));
    }

    /**
     * Two writes are two frames. {@link ProfileStatusAnnouncer} batches to one frame per request
     * because its frame is a snapshot; this one must not, because "the row changed twice" is the fact
     * an audit trail exists to carry.
     */
    @Test
    void twoWritesAreTwoFrames() {
        Profile profile = new Profile();
        profile.setId("profile-7");

        announcer.onAfterSave(new AfterSaveEvent<>(profile, new Document(), "profile"));
        announcer.onAfterSave(new AfterSaveEvent<>(profile, new Document(), "profile"));

        verify(publisher, org.mockito.Mockito.times(2)).publishEntityChange(
            anyString(),
            anyString(),
            eq(EntityChangeAction.UPDATED),
            any(),
            any(Instant.class)
        );
    }

    /**
     * A second save of the same instance is an update, not a second creation — the note left at
     * conversion is consumed once.
     */
    @Test
    void theCreatedNoteIsConsumedRatherThanLeftBehind() {
        Profile profile = new Profile();

        announcer.onBeforeConvert(new BeforeConvertEvent<>(profile, "profile"));
        profile.setId("profile-7");
        announcer.onAfterSave(new AfterSaveEvent<>(profile, new Document(), "profile"));
        announcer.onAfterSave(new AfterSaveEvent<>(profile, new Document(), "profile"));

        verify(publisher).publishEntityChange(anyString(), anyString(), eq(EntityChangeAction.CREATED), any(), any(Instant.class));
        verify(publisher).publishEntityChange(anyString(), anyString(), eq(EntityChangeAction.UPDATED), any(), any(Instant.class));
    }

    /** An entity with no id resolves to nothing to announce, and must not throw into the write path. */
    @Test
    void anEntityWithNoIdIsSkippedRatherThanAnnouncedWithANullId() {
        announcer.onAfterSave(new AfterSaveEvent<>(new Profile(), new Document(), "profile"));

        verify(publisher, never()).publishEntityChange(anyString(), any(), any(), any(), any());
    }

    /**
     * The listener is typed to {@code Object}, so it takes an {@code AfterDeleteEvent<Object>} — but
     * that event's constructor takes a {@code Class<T>}, which pins {@code T} to the entity type and
     * makes the diamond refuse. The cast is the seam between the two and is confined to this helper.
     */
    @SuppressWarnings("unchecked")
    private AfterDeleteEvent<Object> deleteEvent(Document query, Class<?> type, String collection) {
        return new AfterDeleteEvent<>(query, (Class<Object>) type, collection);
    }

    private void authenticateAsGatewayUser() {
        authenticate(
            Jwt.withTokenValue("t").header("alg", "HS512").subject(LOGIN).claim("iss", "hc-professional-gateway").claim("uid", ACCOUNT_ID)
        );
    }

    private void authenticate(Jwt.Builder builder) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(builder.build(), List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
    }
}
