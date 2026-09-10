package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jojoaddison.domain.OrphanedAccountRow;
import net.jojoaddison.repository.OrphanedAccountRowRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The migration's decisions, one test each (backlog.md item 50).
 *
 * <p>Mongo is mocked rather than containerised, and deliberately: what is under test is
 * <b>which of three things happens to a row</b>, and each is a different write. A Testcontainers
 * version would prove the same three branches through a much slower apparatus, and the branch that
 * matters most — quarantine — is the one whose absence looks like success in a database.
 */
class AccountIdMigrationServiceUnitTest {

    private static final String KNOWN_LOGIN = "ama.serwaa";
    private static final String KNOWN_ACCOUNT_ID = "68bd4e2a91c30d5f7a1e4c02";
    private static final String ORPHAN_LOGIN = "since.deleted";

    private MongoTemplate mongoTemplate;
    private GatewayUserClient gatewayUserClient;
    private OrphanedAccountRowRepository orphanedRows;
    private AccountIdMigrationService service;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        gatewayUserClient = mock(GatewayUserClient.class);
        orphanedRows = mock(OrphanedAccountRowRepository.class);
        service = new AccountIdMigrationService(mongoTemplate, gatewayUserClient, orphanedRows);

        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put(KNOWN_LOGIN, KNOWN_ACCOUNT_ID);
        when(gatewayUserClient.loginToAccountId()).thenReturn(mapping);
        when(mongoTemplate.find(any(Query.class), eq(Document.class), anyString())).thenReturn(List.of());
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), anyString())).thenReturn(
            UpdateResult.acknowledged(1, 1L, null)
        );
        when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), anyString())).thenReturn(
            UpdateResult.acknowledged(0, 0L, null)
        );
        when(orphanedRows.findByCollectionNameAndDocumentIdAndFieldName(anyString(), anyString(), anyString())).thenReturn(List.of());
    }

    /** A login the gateway knows becomes that account's {@code User.id}. */
    @Test
    void aResolvableLoginIsRewrittenToTheGatewayUserId() {
        rowsIn("profile", row("profile-7", "account_id", KNOWN_LOGIN));

        AccountIdMigrationService.Report report = service.migrate(false);

        Update update = captureUpdateOn("profile");
        assertThat(update.getUpdateObject().get("$set", org.bson.Document.class).getString("account_id")).isEqualTo(KNOWN_ACCOUNT_ID);
        assertThat(report.totalRewritten()).isEqualTo(1);
        assertThat(report.totalQuarantined()).isZero();
    }

    /**
     * <b>The resolve-to-nothing case, which item 48 declined the whole migration over.</b>
     *
     * <p>The key is cleared and the login is recorded. Two assertions, and neither is optional: an
     * implementation that left the login in place would fail the first, and one that cleared it
     * without recording would fail the second and destroy the only copy of the value.
     *
     * <p>Item 50 § "Do not" forbids the two shortcuts explicitly — <i>do not synthesise a
     * {@code User.id} for a row that has none, and do not fall back to the login once the field is
     * renamed</i> — so the negative assertion is spelled against the login by name rather than
     * against "some other value".
     */
    @Test
    void anUnresolvableLoginIsQuarantinedAndItsKeyCleared() {
        rowsIn("profile", row("profile-9", "account_id", ORPHAN_LOGIN));

        AccountIdMigrationService.Report report = service.migrate(false);

        ArgumentCaptor<OrphanedAccountRow> quarantined = ArgumentCaptor.forClass(OrphanedAccountRow.class);
        verify(orphanedRows).save(quarantined.capture());
        assertThat(quarantined.getValue().getOrphanedValue()).isEqualTo(ORPHAN_LOGIN);
        assertThat(quarantined.getValue().getCollectionName()).isEqualTo("profile");
        assertThat(quarantined.getValue().getDocumentId()).isEqualTo("profile-9");

        Update update = captureUpdateOn("profile");
        assertThat(update.getUpdateObject().containsKey("$unset")).as("the key is cleared, not left holding a login").isTrue();
        assertThat(update.getUpdateObject().containsKey("$set")).as("nothing is written into the key").isFalse();
        assertThat(report.totalQuarantined()).isEqualTo(1);
    }

    /**
     * The negative half of the rule above, stated as a property rather than as an implementation
     * detail: <b>no write anywhere in the run puts a login into an account key.</b>
     *
     * <p>Worth having beside the {@code $unset} assertion because they fail on different mistakes.
     * That one catches "left it alone"; this one catches a future author who "helpfully" writes the
     * login back under a new name, or copies it into a sibling collection.
     */
    @Test
    void noWriteEverSetsAnAccountKeyToALogin() {
        rowsIn("profile", row("profile-9", "account_id", ORPHAN_LOGIN), row("profile-7", "account_id", KNOWN_LOGIN));

        service.migrate(false);

        for (Update update : allUpdates()) {
            Document set = update.getUpdateObject().get("$set", Document.class);
            if (set != null) {
                assertThat(set.values()).doesNotContain(ORPHAN_LOGIN, KNOWN_LOGIN);
            }
        }
    }

    /**
     * A second run is a no-op, and the test for it is the value rather than a marker document — a
     * marker can be true while the data is half-moved.
     */
    @Test
    void aRowAlreadyHoldingAnAccountIdIsLeftAlone() {
        rowsIn("profile", row("profile-7", "account_id", KNOWN_ACCOUNT_ID));

        AccountIdMigrationService.Report report = service.migrate(false);

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq("profile"));
        verify(orphanedRows, never()).save(any());
        assertThat(report.collections().stream().mapToInt(AccountIdMigrationService.CollectionReport::alreadyMigrated).sum()).isEqualTo(1);
    }

    /** A dry run reports what it would do and writes nothing at all. */
    @Test
    void aDryRunWritesNothing() {
        rowsIn("profile", row("profile-7", "account_id", KNOWN_LOGIN), row("profile-9", "account_id", ORPHAN_LOGIN));

        AccountIdMigrationService.Report report = service.migrate(true);

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), anyString());
        verify(mongoTemplate, never()).updateMulti(any(Query.class), any(Update.class), anyString());
        verify(orphanedRows, never()).save(any());
        assertThat(report.dryRun()).isTrue();
        assertThat(report.totalRewritten()).isEqualTo(1);
        assertThat(report.totalQuarantined()).isEqualTo(1);
    }

    /**
     * <b>Every collection, not just {@code profile}.</b> The account key is a lookup key, so a
     * clinician whose profile has moved and whose application has not cannot be found by either —
     * item 53's review calls a row-by-row fill destructive rather than merely slow, for this reason.
     *
     * <p>Asserted by enumerating what the service reads rather than by naming seven collections
     * again, so adding an eighth target does not need this test edited — only the list it derives
     * from, which is the source of truth.
     */
    @Test
    void everyDeclaredTargetIsRead() {
        service.migrate(true);

        for (AccountIdMigrationService.FieldTarget target : AccountIdMigrationService.TARGETS) {
            verify(mongoTemplate).find(any(Query.class), eq(Document.class), eq(target.collection()));
        }
        assertThat(AccountIdMigrationService.TARGETS)
            .as("the two targets a grep for 'accountId' would have missed")
            .anyMatch(t -> t.field().equals("recipient_id"))
            .anyMatch(t -> t.field().equals("sender_id"));
    }

    /**
     * An unreadable gateway aborts before anything is written. A partial mapping would quarantine
     * rows that have owners, which is the one outcome here that cannot be undone from the data.
     */
    @Test
    void anUnreachableGatewayWritesNothing() {
        when(gatewayUserClient.loginToAccountId()).thenThrow(new GatewayUserClient.GatewayUnavailableException("connection refused", null));

        assertThatThrownBy(() -> service.migrate(false)).isInstanceOf(GatewayUserClient.GatewayUnavailableException.class);

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), anyString());
        verify(orphanedRows, never()).save(any());
    }

    /** The shadow field item 48 added is unset once the value it held lives in {@code account_id}. */
    @Test
    void theRetiredAccountUidFieldIsDropped() {
        service.migrate(false);

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateMulti(any(Query.class), update.capture(), eq("profile"));
        assertThat(update.getValue().getUpdateObject().get("$unset", Document.class)).containsKey("account_uid");
    }

    // ------------------------------------------------------------------ helpers

    private static Document row(String id, String field, String value) {
        return new Document("_id", id).append(field, value);
    }

    private void rowsIn(String collection, Document... rows) {
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq(collection))).thenReturn(new ArrayList<>(List.of(rows)));
    }

    private Update captureUpdateOn(String collection) {
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), update.capture(), eq(collection));
        return update.getValue();
    }

    private List<Update> allUpdates() {
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, org.mockito.Mockito.atLeastOnce()).updateFirst(any(Query.class), update.capture(), anyString());
        return update.getAllValues();
    }
}
