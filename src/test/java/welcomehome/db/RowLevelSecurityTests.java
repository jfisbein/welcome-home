package welcomehome.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;

@IntegrationTest
class RowLevelSecurityTests {

    /** Every table with an owner_id but Spring Security's passkey store, which is read before anyone is signed in. */
    List<String> ownerTables;

    @Autowired
    OwnerTransactions ownerTransactions;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    OwnerRows alice;
    OwnerRows bob;

    @BeforeEach
    void insertTwoOwners() {
        var migrator = TestDatabaseConfiguration.asMigrator(postgres);
        var data = new TestData(migrator);
        data.deleteOwners();
        ownerTables = data.ownerTables().stream().filter(table -> !table.equals("user_entities")).toList();
        alice = OwnerRows.insert(data, "alice@example.com", 1L);
        bob = OwnerRows.insert(data, "bob@example.com", 2L);
    }

    /** So that a table added later is tried here without anyone remembering to: its rows come from TestData. */
    @Test
    void theTablesTriedAreEveryTableThatHoldsOwnersData() {
        assertThat(ownerTables).contains("nuki_connection", "lock", "access_link", "access_link_schedule",
                "access_event", "notification_preference", "owner_activity");
    }

    @Test
    void withoutOwnerContextAppUserSeesNoRows() {
        for (var table : ownerTables) {
            assertThat(ownerIds(table)).as(table).isEmpty();
        }
    }

    @Test
    void insideOwnerTransactionAppUserSeesOnlyOwnRows() {
        ownerTransactions.run(alice.ownerId(), () -> {
            for (var table : ownerTables) {
                assertThat(ownerIds(table)).as(table).containsExactly(alice.ownerId());
            }
        });

        for (var table : ownerTables) {
            assertThat(ownerIds(table)).as(table + " after the transaction").isEmpty();
        }
    }

    @Test
    void updateOfAnotherOwnersRowChangesNothing() {
        var updated = ownerTransactions.execute(alice.ownerId(), () ->
                jdbc.sql("UPDATE lock SET name = 'taken' WHERE id = :id")
                        .param("id", bob.lockId())
                        .update());

        assertThat(updated).isZero();
    }

    @Test
    void insertForAnotherOwnerIsRejected() {
        assertThatThrownBy(() -> ownerTransactions.run(alice.ownerId(), () ->
                jdbc.sql("""
                                INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                                VALUES (:ownerId, 3, 'Front door', 'Europe/Madrid')
                                """)
                        .param("ownerId", bob.ownerId())
                        .update()))
                .rootCause()
                .hasMessageContaining("violates row-level security policy");
    }

    @Test
    void referenceToAnotherOwnersLockIsRejected() {
        assertThatThrownBy(() -> ownerTransactions.run(alice.ownerId(), () ->
                jdbc.sql("""
                                INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until)
                                VALUES (:ownerId, :lockId, 'Guest', now(), now() + interval '1 day')
                                """)
                        .param("ownerId", alice.ownerId())
                        .param("lockId", bob.lockId())
                        .update()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("access_link_lock_fkey");
    }

    @Test
    void resolveLinkReturnsTheLinkAndItsLockWithoutOwnerContext() {
        var link = resolveLink(bob.linkId());

        assertThat(link).contains(new ResolvedLink(bob.linkId(), bob.ownerId(), bob.lockId(), 1, "Home", true));
    }

    @Test
    void resolveLinkReturnsNothingForAnUnknownLink() {
        assertThat(resolveLink(UUID.randomUUID())).isEmpty();
    }

    private Optional<ResolvedLink> resolveLink(UUID linkId) {
        return jdbc.sql("""
                        SELECT link_id, owner_id, lock_id, token_version, lock_name, lock_active
                        FROM resolve_link(:linkId)
                        """)
                .param("linkId", linkId)
                .query(ResolvedLink.class)
                .optional();
    }

    private List<UUID> ownerIds(String table) {
        return jdbc.sql("SELECT owner_id FROM " + table).query(UUID.class).list();
    }

    record ResolvedLink(UUID linkId, UUID ownerId, UUID lockId, int tokenVersion, String lockName, boolean lockActive) {
    }

    /** One owner with one row in every table that has owner_id, inserted as migrator. */
    record OwnerRows(UUID ownerId, UUID lockId, UUID linkId) {

        static OwnerRows insert(TestData data, String email, long nukiSmartlockId) {
            var owner = data.owner(email);
            var rows = data.aRowInEveryTable(owner, nukiSmartlockId);
            return new OwnerRows(owner, rows.lockId(), rows.linkId());
        }
    }
}
