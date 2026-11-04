package welcomehome.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
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
    OwnerActivityDao activity;

    @Autowired
    OwnerTransactions ownerTransactions;

    @Autowired
    PostgreSQLContainer postgres;

    /** The app's own connection, as app_user. */
    @Autowired
    JdbcClient jdbc;

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

        // The one from 364 days ago stays, without its IP address.
        assertThat(attempts(alice)).containsExactly("opened|-|-|-", "opened|-|198.51.100.3|-");
        assertThat(attempts(bob)).containsExactly("opened|-|-|-", "opened|-|198.51.100.3|-");
    }

    @Test
    void retentionComesFromTheConfiguration() {
        var link = insertLink("alice@example.com");
        insertEvent(link, "31 days", "198.51.100.1");
        insertEvent(link, "29 days", "198.51.100.2");

        new EventPurgeJob(accessEvents, activity, ownerTransactions, new AuditProperties(Duration.ofDays(30), Duration.ofDays(30))).purgeAll();

        assertThat(remainingIps(link)).containsExactly("198.51.100.2");
    }

    /** The browser, which earlier releases stored and nothing shows, goes from every attempt. */
    @Test
    void attemptsForgetTheGuestsIpAddressAfterNinetyDaysButKeepTheirCountry() {
        var alice = insertLink("alice@example.com");
        var bob = insertLink("bob@example.com");
        for (var link : List.of(alice, bob)) {
            insertEvent(link, "91 days", "198.51.100.1");
            insertEvent(link, "89 days", "198.51.100.2");
        }
        migrator.sql("UPDATE access_event SET country = 'ES', user_agent = 'Firefox'").update();

        job.purgeAll();

        for (var link : List.of(alice, bob)) {
            assertThat(attempts(link)).containsExactly("opened|ES|-|-", "opened|ES|198.51.100.2|-");
        }
    }

    /** A distance to a known door says where the guest was too. */
    @Test
    void attemptsForgetTheirDistanceToTheDoorWithTheIpAddress() {
        var link = insertLink("alice@example.com");
        insertEvent(link, "91 days", "198.51.100.1");
        insertEvent(link, "89 days", "198.51.100.2");
        migrator.sql("UPDATE access_event SET distance_m = 3000, accuracy_m = 20, location_confirmed = true").update();

        job.purgeAll();

        assertThat(migrator.sql("""
                        SELECT concat_ws('|', coalesce(distance_m::text, '-'), coalesce(accuracy_m::text, '-'),
                                         location_confirmed)
                        FROM access_event ORDER BY occurred_at
                        """)
                .query(String.class).list()).containsExactly("-|-|t", "3000|20|t");
    }

        @Test
    void ipRetentionComesFromTheConfiguration() {
        var link = insertLink("alice@example.com");
        insertEvent(link, "31 days", "198.51.100.1");
        insertEvent(link, "29 days", "198.51.100.2");

        new EventPurgeJob(accessEvents, activity, ownerTransactions, new AuditProperties(Duration.ofDays(365), Duration.ofDays(30)))
                .purgeAll();

        assertThat(attempts(link)).containsExactly("opened|-|-|-", "opened|-|198.51.100.2|-");
    }

    @Test
    void theAppMayForgetAnAttemptsIpAddressButChangeNothingElse() {
        jdbc.sql("UPDATE access_event SET ip = NULL, user_agent = NULL, distance_m = NULL, accuracy_m = NULL").update();
        assertThatThrownBy(() -> jdbc.sql("UPDATE access_event SET reason = 'opened'").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied for table access_event");
    }

    @Test
    void ownersActivityIsDeletedAfterAYearAndForgetsItsIpAddressAfterNinetyDays() {
        var alice = data.owner("alice@example.com");
        var bob = data.owner("bob@example.com");
        for (var owner : List.of(alice, bob)) {
            for (var age : List.of("366 days", "91 days", "89 days")) {
                migrator.sql("""
                                INSERT INTO owner_activity (owner_id, occurred_at, kind, ip, country)
                                VALUES (?, now() - CAST(? AS interval), 'SIGNED_IN', '198.51.100.1', 'ES')
                                """)
                        .params(owner, age).update();
            }
        }

        job.purgeAll();

        for (var owner : List.of(alice, bob)) {
            assertThat(migrator.sql("""
                            SELECT concat_ws('|', country, coalesce(host(ip), '-')) FROM owner_activity
                            WHERE owner_id = ? ORDER BY occurred_at
                            """)
                    .param(owner).query(String.class).list())
                    .containsExactly("ES|-", "ES|198.51.100.1");
        }
    }

    @Test
    void theAppMayForgetAnActivitysIpAddressButChangeNothingElse() {
        jdbc.sql("UPDATE owner_activity SET ip = NULL").update();
        assertThatThrownBy(() -> jdbc.sql("UPDATE owner_activity SET kind = 'SIGNED_IN'").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied for table owner_activity");
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

    /** Each of the link's attempts, oldest first, as "reason|country|ip|browser", with "-" for none. */
    private List<String> attempts(UUID linkId) {
        return migrator.sql("""
                        SELECT concat_ws('|', reason, coalesce(country, '-'), coalesce(host(ip), '-'),
                                         coalesce(user_agent, '-'))
                        FROM access_event WHERE link_id = ? ORDER BY occurred_at
                        """)
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
