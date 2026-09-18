package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.broker.EntityChangeAction;
import net.jojoaddison.domain.Category;
import net.jojoaddison.domain.Team;
import net.jojoaddison.repository.CategoryRepository;
import net.jojoaddison.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * <b>A bare repository write announces on {@code professional.event}</b> — backlog.md item 141.
 *
 * <p>This is the one property neither unit test can reach, and it is the property the whole design
 * rests on. {@code EntityChangeAnnouncerTest} calls the listener's methods directly, so it proves
 * what the listener decides once something invokes it; {@code EntityChangeEventTest} proves what the
 * frame carries. <b>Neither would notice if Spring never registered the listener at all</b> — if the
 * {@code @Component} were dropped, if {@code AbstractMongoEventListener} stopped being picked up by
 * the {@code MongoTemplate} this application builds, or if the bean were defined somewhere the
 * context does not scan. Every case here would still be green and nothing would ever be published.
 *
 * <p><b>It writes through repositories and names no resource and no service</b>, deliberately, for
 * the reason {@code ProfileStatusOnEveryWriteIT} gives about its own generalising case: the claim
 * being made is *every* entity CRUD event, and a test that drove today's endpoints would prove only
 * that today's endpoints work. A collection generated next month reaches the database the same way
 * and is covered here without anybody editing this file.
 *
 * <p>{@link Category} and {@link Team} are used precisely because <b>nothing in this feature knows
 * they exist</b> — they carry no {@code accountId}, they are not one of the three documents
 * {@code ProfileStatusAnnouncer} watches, and no code in item 141 mentions them. A listener typed to
 * a list of entities would fail here; one typed to {@code Object} does not.
 *
 * <p>The publisher is mocked, so this exercises the observation half against real Mongo and asserts
 * the five values handed over. What happens after that — the envelope, the executor, the binding — is
 * the other three test classes' subject.
 */
@IntegrationTest
class EntityChangeOnEveryWriteIT {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TeamRepository teamRepository;

    @MockitoBean
    private DomainEventPublisher publisher;

    @BeforeEach
    void resetPublisher() {
        clearInvocations(publisher);
    }

    @AfterEach
    void clean() {
        categoryRepository.deleteAll();
        teamRepository.deleteAll();
    }

    /**
     * The whole mechanism in one case: an ordinary save of a collection this feature has never heard
     * of is announced, with its own type name and the id Mongo assigned it.
     */
    @Test
    void aBareRepositorySaveAnnouncesWithTheEntityTypeAndTheIdMongoAssigned() {
        Category saved = categoryRepository.save(category("Physiotherapy"));

        ArgumentCaptor<String> entityId = ArgumentCaptor.forClass(String.class);
        verify(publisher).publishEntityChange(
            eq("Category"),
            entityId.capture(),
            eq(EntityChangeAction.CREATED),
            any(),
            any(Instant.class)
        );
        assertThat(entityId.getValue()).isEqualTo(saved.getId()).isNotNull();
    }

    /**
     * A second write of the same row is an update, not a second creation.
     *
     * <p>This is the case that fails if the insert/replace decision is taken anywhere other than
     * before conversion — by {@code onAfterSave} every entity has an id and the two are
     * indistinguishable, so an implementation that looked there would report {@code CREATED} twice.
     */
    @Test
    void savingTheSameRowAgainIsAnUpdate() {
        Category saved = categoryRepository.save(category("Physiotherapy"));
        clearInvocations(publisher);

        saved.setName("Physiotherapy and rehabilitation");
        categoryRepository.save(saved);

        verify(publisher).publishEntityChange(eq("Category"), eq(saved.getId()), eq(EntityChangeAction.UPDATED), any(), any(Instant.class));
    }

    /**
     * ⚠ A delete produces a frame too, from the real delete path.
     *
     * <p>Worth an integration case of its own rather than only a unit one, because the id arrives here
     * as a <em>mapped</em> query: Spring Data converts a generated 24-character hex id to an
     * {@code ObjectId} on the way to the listener. A reader matching only {@code String} sees a query
     * naming no single id and skips every delete — silently, and only against a real database.
     */
    @Test
    void aDeleteAnnouncesToo() {
        Category saved = categoryRepository.save(category("Physiotherapy"));
        clearInvocations(publisher);

        categoryRepository.delete(saved);

        verify(publisher).publishEntityChange(eq("Category"), eq(saved.getId()), eq(EntityChangeAction.DELETED), any(), any(Instant.class));
    }

    /**
     * A second, unrelated collection — the claim is every entity, not every entity somebody listed.
     */
    @Test
    void aDifferentCollectionAnnouncesWithoutBeingNamedAnywhere() {
        Team team = new Team();
        team.setName("Night team");

        Team saved = teamRepository.save(team);

        verify(publisher).publishEntityChange(eq("Team"), eq(saved.getId()), eq(EntityChangeAction.CREATED), any(), any(Instant.class));
    }

    /**
     * No security context behind these writes, so no actor — never a placeholder in the account-id
     * space. This is the shape every scheduler, migration and startup runner writes in.
     */
    @Test
    void aWriteWithNoCallerCarriesNoActor() {
        categoryRepository.save(category("Physiotherapy"));

        verify(publisher).publishEntityChange(anyString(), anyString(), any(), eq(null), any(Instant.class));
    }

    private Category category(String name) {
        Category category = new Category();
        category.setName(name);
        return category;
    }
}
