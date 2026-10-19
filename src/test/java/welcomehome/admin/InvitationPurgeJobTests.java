package welcomehome.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;

@IntegrationTest
class InvitationPurgeJobTests {

    @Autowired
    InvitationPurgeJob job;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;

    @BeforeEach
    void deleteInvitations() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM invitation").update();
    }

    @Test
    void invitationsAreDeletedThirtyDaysAfterTheyExpireUsedOrNot() {
        insert("expired 31 days ago, unused", "now() - interval '31 days'", false);
        insert("expired 31 days ago, used", "now() - interval '31 days'", true);
        insert("expired 29 days ago", "now() - interval '29 days'", true);
        insert("still valid", "now() + interval '1 day'", false);

        job.purge();

        assertThat(migrator.sql("SELECT note FROM invitation").query(String.class).list())
                .containsExactlyInAnyOrder("expired 29 days ago", "still valid");
    }

    private void insert(String note, String expiresAt, boolean used) {
        migrator.sql("INSERT INTO invitation (token_hash, note, expires_at, used_at) "
                        + "VALUES (sha256(convert_to(:note, 'UTF8')), :note, " + expiresAt + ", "
                        + (used ? expiresAt + " - interval '1 hour'" : "NULL") + ")")
                .param("note", note).update();
    }
}
