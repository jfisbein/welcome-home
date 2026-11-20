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
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;

/** What ties passkeys to their owner in the database, as Spring Security writes them knowing only an email. */
@IntegrationTest
class PasskeyOwnerTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;
    UUID alice;
    UUID bob;

    @BeforeEach
    void insertAliceAndBob() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        data.deleteOwners();
        alice = data.owner(ALICE);
        bob = data.owner(BOB);
    }

    @Test
    void aUserWrittenUnderItsOwnersIdGetsItsOwner() {
        insertUser("alice-id", ALICE);

        assertThat(ownerOf("alice-id")).isEqualTo(alice);
    }

    @Test
    void aUserRenamedToAnotherOwnersIdFollowsIt() {
        insertUser("alice-id", ALICE);

        migrator.sql("UPDATE user_entities SET name = ? WHERE id = 'alice-id'").param(bob.toString()).update();

        assertThat(ownerOf("alice-id")).isEqualTo(bob);
    }

    @Test
    void aUserUnderANameThatIsNoOwnersIdIsRefused() {
        for (var name : List.of(ALICE, "nobody@example.com", new UUID(0, 1).toString())) {
            assertThatThrownBy(() -> migrator
                    .sql("INSERT INTO user_entities (id, name, display_name) VALUES ('some-id', ?, ?)")
                    .params(name, ALICE).update()).as(name).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void anOwnerHasOneUser() {
        insertUser("alice-id", ALICE);

        assertThatThrownBy(() -> insertUser("alice-again", ALICE)).isInstanceOf(DataIntegrityViolationException.class);
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
                .params(alice.toString(), ALICE).update();

        assertThat(ownerOf("alice-id")).isEqualTo(alice);
    }

    private void insertUser(String id, String email) {
        data.passkeyUser(id, email);
    }

    private void insertPasskey(String credentialId, String userId) {
        data.passkey(credentialId, userId, "Passkey");
    }

    private UUID ownerOf(String userId) {
        return migrator.sql("SELECT owner_id FROM user_entities WHERE id = ?").param(userId)
                .query((rs, row) -> rs.getObject("owner_id", UUID.class)).list().getFirst();
    }
}
