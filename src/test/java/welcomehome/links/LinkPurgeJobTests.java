package welcomehome.links;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;

@IntegrationTest
class LinkPurgeJobTests {

    @Autowired
    LinkPurgeJob job;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID alice;
    UUID casa;

    @BeforeEach
    void insertAliceWithALock() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        alice = insertOwner("alice@example.com");
        casa = insertLock(alice);
    }

    @Test
    void aLinkGoesAYearAfterItExpiredAndNotBefore() {
        insertLink(alice, casa, "Hace dos años", "now() - interval '2 years'", "NULL");
        insertLink(alice, casa, "Hace un mes", "now() - interval '1 month'", "NULL");
        insertLink(alice, casa, "Activo", "now() + interval '1 day'", "NULL");

        job.purgeAll();

        assertThat(labels()).containsExactly("Activo", "Hace un mes");
    }

    @Test
    void aLinkCancelledLongAgoGoesThoughItsDatesHadNotEnded() {
        insertLink(alice, casa, "Anulado hace dos años", "now() + interval '1 year'", "now() - interval '2 years'");
        insertLink(alice, casa, "Anulado ayer", "now() + interval '1 year'", "now() - interval '1 day'");

        job.purgeAll();

        assertThat(labels()).containsExactly("Anulado ayer");
    }

    @Test
    void aLinkWithARecentAttemptStaysUntilThatAttemptIsOldToo() {
        var triedLately = insertLink(alice, casa, "Lo probaron ayer", "now() - interval '2 years'", "NULL");
        var triedLongAgo = insertLink(alice, casa, "Lo probaron hace tiempo", "now() - interval '2 years'", "NULL");
        insertEvent(alice, casa, triedLately, "now() - interval '1 day'");
        insertEvent(alice, casa, triedLongAgo, "now() - interval '2 years'");

        job.purgeAll();

        assertThat(labels()).containsExactly("Lo probaron ayer");
        assertThat(migrator.sql("SELECT count(*) FROM access_event").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void itsScheduleGoesWithIt() {
        var link = insertLink(alice, casa, "Con horario", "now() - interval '2 years'", "NULL");
        migrator.sql("""
                        INSERT INTO access_link_schedule (owner_id, link_id, day_of_week, opens_at, closes_at)
                        VALUES (?, ?, 1, '09:00', '17:00')
                        """)
                .params(alice, link).update();

        job.purgeAll();

        assertThat(migrator.sql("SELECT count(*) FROM access_link_schedule").query(Long.class).single()).isZero();
    }

    @Test
    void everyOwnersLinksGo() {
        var bob = insertOwner("bob@example.com");
        insertLink(bob, insertLock(bob), "De Bob", "now() - interval '2 years'", "NULL");
        insertLink(alice, casa, "De Alice", "now() - interval '2 years'", "NULL");

        job.purgeAll();

        assertThat(labels()).isEmpty();
    }

    private List<String> labels() {
        return migrator.sql("SELECT label FROM access_link ORDER BY label").query(String.class).list();
    }

    private UUID insertOwner(String email) {
        return migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id").param(email).query(UUID.class).single();
    }

    private UUID insertLock(UUID owner) {
        return migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, 17, 'Casa', 'Europe/Madrid') RETURNING id
                        """)
                .param(owner).query(UUID.class).single();
    }

    private UUID insertLink(UUID owner, UUID lock, String label, String validUntil, String revokedAt) {
        return migrator.sql("INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until, revoked_at) "
                        + "VALUES (?, ?, ?, now() - interval '3 years', " + validUntil + ", " + revokedAt
                        + ") RETURNING id")
                .params(owner, lock, label).query(UUID.class).single();
    }

    private void insertEvent(UUID owner, UUID lock, UUID link, String occurredAt) {
        migrator.sql("INSERT INTO access_event (owner_id, link_id, lock_id, result, reason, occurred_at) "
                        + "VALUES (?, ?, ?, 'DENIED', 'expired', " + occurredAt + ")")
                .params(owner, link, lock).update();
    }
}
