package welcomehome.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.db.OwnerTransactions;
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as the locks tests, so they share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class EventPurgeJobTests {

    @Autowired
    EventPurgeJob job;

    @Autowired
    AccessEventDao accessEvents;

    @Autowired
    OwnerTransactions ownerTransactions;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;

    @BeforeEach
    void emptyDatabase() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        migrator.sql("DELETE FROM owner").update();
    }

    @Test
    void purgeDeletesEveryOwnersAttemptsOlderThanAYear() {
        var alice = insertLink("alice@example.com");
        var bob = insertLink("bob@example.com");
        for (var link : List.of(alice, bob)) {
            insertEvent(link, "366 days", "198.51.100.1");
            insertEvent(link, "364 days", "198.51.100.2");
            insertEvent(link, "1 hour", "198.51.100.3");
        }

        job.purgeAll();

        assertThat(remainingIps(alice)).containsExactly("198.51.100.2", "198.51.100.3");
        assertThat(remainingIps(bob)).containsExactly("198.51.100.2", "198.51.100.3");
    }

    @Test
    void retentionComesFromTheConfiguration() {
        var link = insertLink("alice@example.com");
        insertEvent(link, "31 days", "198.51.100.1");
        insertEvent(link, "29 days", "198.51.100.2");

        new EventPurgeJob(accessEvents, ownerTransactions, new AuditProperties(Duration.ofDays(30))).purgeAll();

        assertThat(remainingIps(link)).containsExactly("198.51.100.2");
    }

    @Test
    void purgeWithNothingToDeleteChangesNothing() {
        var link = insertLink("alice@example.com");
        insertEvent(link, "1 day", "198.51.100.1");

        job.purgeAll();
        job.purgeAll();

        assertThat(remainingIps(link)).containsExactly("198.51.100.1");
    }

    private List<String> remainingIps(UUID linkId) {
        return migrator.sql("SELECT host(ip) FROM access_event WHERE link_id = ? ORDER BY occurred_at")
                .param(linkId).query(String.class).list();
    }

    private void insertEvent(UUID linkId, String age, String ip) {
        data.attempt(linkId, "now() - interval '" + age + "'", "OK", "opened", ip);
    }

    /** An owner with a lock and a link to it. */
    private UUID insertLink(String email) {
        return data.link(data.lock(data.owner(email), 17, "Casa"), "Ana", "now()", TestData.IN_A_DAY);
    }
}
