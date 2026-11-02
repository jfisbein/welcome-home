package welcomehome.links;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;

@IntegrationTest
class LinkPurgeJobTests {

    @Autowired
    LinkPurgeJob job;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;
    UUID alice;
    UUID casa;

    @BeforeEach
    void insertAliceWithALock() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        migrator.sql("DELETE FROM owner").update();
        alice = insertOwner("alice@example.com");
        casa = insertLock(alice);
    }

    @Test
    void aLinkGoesAYearAfterItExpiredAndNotBefore() {
        insertLink(casa, "Hace dos años", "now() - interval '2 years'", "NULL");
        insertLink(casa, "Hace un mes", "now() - interval '1 month'", "NULL");
        insertLink(casa, "Activo", "now() + interval '1 day'", "NULL");

        job.purgeAll();

        assertThat(labels()).containsExactly("Activo", "Hace un mes");
    }

    @Test
    void aLinkCancelledLongAgoGoesThoughItsDatesHadNotEnded() {
        insertLink(casa, "Anulado hace dos años", "now() + interval '1 year'", "now() - interval '2 years'");
        insertLink(casa, "Anulado ayer", "now() + interval '1 year'", "now() - interval '1 day'");

        job.purgeAll();

        assertThat(labels()).containsExactly("Anulado ayer");
    }

    @Test
    void aLinkWithARecentAttemptStaysUntilThatAttemptIsOldToo() {
        var triedLately = insertLink(casa, "Lo probaron ayer", "now() - interval '2 years'", "NULL");
        var triedLongAgo = insertLink(casa, "Lo probaron hace tiempo", "now() - interval '2 years'", "NULL");
        insertEvent(triedLately, "now() - interval '1 day'");
        insertEvent(triedLongAgo, "now() - interval '2 years'");

        job.purgeAll();

        assertThat(labels()).containsExactly("Lo probaron ayer");
        assertThat(migrator.sql("SELECT count(*) FROM access_event").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void itsScheduleGoesWithIt() {
        var link = insertLink(casa, "Con horario", "now() - interval '2 years'", "NULL");
        data.schedule(link, DayOfWeek.MONDAY, "09:00", "17:00");

        job.purgeAll();

        assertThat(migrator.sql("SELECT count(*) FROM access_link_schedule").query(Long.class).single()).isZero();
    }

    @Test
    void everyOwnersLinksGo() {
        var bob = insertOwner("bob@example.com");
        insertLink(insertLock(bob), "De Bob", "now() - interval '2 years'", "NULL");
        insertLink(casa, "De Alice", "now() - interval '2 years'", "NULL");

        job.purgeAll();

        assertThat(labels()).isEmpty();
    }

    private List<String> labels() {
        return migrator.sql("SELECT label FROM access_link ORDER BY label").query(String.class).list();
    }

    private UUID insertOwner(String email) {
        return data.owner(email);
    }

    private UUID insertLock(UUID owner) {
        return data.lock(owner, 17, "Casa");
    }

    private UUID insertLink(UUID lock, String label, String validUntil, String revokedAt) {
        return data.link(lock, label, "now() - interval '3 years'", validUntil,
                "NULL".equals(revokedAt) ? null : revokedAt);
    }

    private void insertEvent(UUID link, String occurredAt) {
        data.attempt(link, occurredAt, "DENIED", "expired", null);
    }
}
