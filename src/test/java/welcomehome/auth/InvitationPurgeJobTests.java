package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.TestData;
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
        new TestData(migrator).deleteOwners();
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
        new TestData(migrator).invitation(note, null, expiresAt, used);
    }
}
