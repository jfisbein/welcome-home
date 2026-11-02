package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;

/** What ties passkeys to their owner in the database, as Spring Security writes them knowing only an email. */
@IntegrationTest
class PasskeyOwnerTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID alice;
    UUID bob;

    @BeforeEach
    void insertAliceAndBob() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        for (var table : List.of("user_credentials", "user_entities", "owner")) {
            migrator.sql("DELETE FROM " + table).update();
        }
        alice = insertOwner(ALICE);
        bob = insertOwner(BOB);
    }

    @Test
    void aUserWrittenWithOnlyAnEmailGetsItsOwner() {
        insertUser("alice-id", ALICE);

        assertThat(ownerOf("alice-id")).isEqualTo(alice);
    }

    @Test
    void aUserWhoseEmailChangesFollowsIt() {
        insertUser("alice-id", ALICE);

        migrator.sql("UPDATE user_entities SET name = ? WHERE id = 'alice-id'").param(BOB).update();

        assertThat(ownerOf("alice-id")).isEqualTo(bob);
    }

    @Test
    void aUserWithTheEmailOfNoOwnerHasNone() {
        insertUser("nobody-id", "nobody@example.com");

        assertThat(ownerOf("nobody-id")).isNull();
    }

    @Test
    void deletingAnOwnerDeletesTheirPasskeysAndNoOneElses() {
        insertUser("alice-id", ALICE);
        insertPasskey("alice-passkey", "alice-id");
        insertUser("bob-id", BOB);
        insertPasskey("bob-passkey", "bob-id");

        migrator.sql("DELETE FROM owner WHERE id = ?").param(alice).update();

        assertThat(migrator.sql("SELECT id FROM user_entities").query(String.class).list()).containsExactly("bob-id");
        assertThat(migrator.sql("SELECT credential_id FROM user_credentials").query(String.class).list())
                .containsExactly("bob-passkey");
    }

    @Test
    void aPasskeyNeedsItsUser() {
        assertThatThrownBy(() -> insertPasskey("orphan-passkey", "no-such-id"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theApplicationMayWriteAUser() {
        JdbcClient.create(TestDatabaseConfiguration.newSessionsAsAppUser(postgres))
                .sql("INSERT INTO user_entities (id, name, display_name) VALUES ('alice-id', ?, ?)")
                .params(ALICE, ALICE).update();

        assertThat(ownerOf("alice-id")).isEqualTo(alice);
    }

    private UUID insertOwner(String email) {
        return migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id").param(email).query(UUID.class).single();
    }

    private void insertUser(String id, String email) {
        migrator.sql("INSERT INTO user_entities (id, name, display_name) VALUES (?, ?, ?)")
                .params(id, email, email).update();
    }

    private void insertPasskey(String credentialId, String userId) {
        migrator.sql("""
                        INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible,
                                                      backup_state, label)
                        VALUES (?, ?, '\\x01', false, false, 'Passkey')
                        """)
                .params(credentialId, userId).update();
    }

    private UUID ownerOf(String userId) {
        return migrator.sql("SELECT owner_id FROM user_entities WHERE id = ?").param(userId)
                .query((rs, row) -> rs.getObject("owner_id", UUID.class)).list().getFirst();
    }
}
