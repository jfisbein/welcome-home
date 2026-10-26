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
import welcomehome.TestDatabaseConfiguration;

@IntegrationTest
class RowLevelSecurityTests {

    static final List<String> OWNER_TABLES = List.of("nuki_connection", "lock", "access_link", "access_event");

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
        migrator.sql("DELETE FROM owner").update();
        alice = OwnerRows.insert(migrator, "alice@example.com", 1L);
        bob = OwnerRows.insert(migrator, "bob@example.com", 2L);
    }

    @Test
    void withoutOwnerContextAppUserSeesNoRows() {
        for (var table : OWNER_TABLES) {
            assertThat(ownerIds(table)).as(table).isEmpty();
        }
    }

    @Test
    void insideOwnerTransactionAppUserSeesOnlyOwnRows() {
        ownerTransactions.run(alice.ownerId(), () -> {
            for (var table : OWNER_TABLES) {
                assertThat(ownerIds(table)).as(table).containsExactly(alice.ownerId());
            }
        });

        for (var table : OWNER_TABLES) {
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
    record OwnerRows(UUID ownerId, UUID connectionId, UUID lockId, UUID linkId) {

        static OwnerRows insert(JdbcClient migrator, String email, long nukiSmartlockId) {
            var rows = new OwnerRows(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            migrator.sql("INSERT INTO owner (id, email) VALUES (?, ?)")
                    .params(rows.ownerId(), email)
                    .update();
            migrator.sql("INSERT INTO nuki_connection (id, owner_id, access_token_encrypted, status) VALUES (?, ?, ?, 'OK')")
                    .params(rows.connectionId(), rows.ownerId(), new byte[] {1})
                    .update();
            migrator.sql("""
                            INSERT INTO lock (id, owner_id, connection_id, nuki_smartlock_id, name, time_zone)
                            VALUES (?, ?, ?, ?, 'Home', 'Europe/Madrid')
                            """)
                    .params(rows.lockId(), rows.ownerId(), rows.connectionId(), nukiSmartlockId)
                    .update();
            migrator.sql("""
                            INSERT INTO access_link (id, owner_id, lock_id, label, valid_from, valid_until)
                            VALUES (?, ?, ?, 'Guest', now(), now() + interval '1 day')
                            """)
                    .params(rows.linkId(), rows.ownerId(), rows.lockId())
                    .update();
            migrator.sql("INSERT INTO access_event (owner_id, link_id, lock_id, result) VALUES (?, ?, ?, 'OK')")
                    .params(rows.ownerId(), rows.linkId(), rows.lockId())
                    .update();
            return rows;
        }
    }
}
