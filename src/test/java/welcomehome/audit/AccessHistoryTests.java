package welcomehome.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as the locks tests, so they share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class AccessHistoryTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID casa;
    UUID garaje;
    UUID ana;
    UUID bea;
    UUID carlos;
    UUID bobsLock;
    UUID bobsLink;

    @BeforeEach
    void insertLocksAndLinks() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        var alice = insertOwner(ALICE);
        casa = insertLock(alice, 17, "Casa", "Europe/Madrid");
        garaje = insertLock(alice, 18, "Garaje", "Europe/Madrid");
        ana = insertLink(casa, "Ana");
        bea = insertLink(casa, "Bea");
        carlos = insertLink(garaje, "Carlos");
        bobsLock = insertLock(insertOwner(BOB), 19, "Casa de Bob", "Europe/Madrid");
        bobsLink = insertLink(bobsLock, "Invitado de Bob");
    }

    @Test
    void lockHistoryShowsItsAttemptsNewestFirstInTheLocksTimeZone() {
        insertEvent(ana, "2030-07-01T10:00:00Z", "DENIED", "not-yet-valid", "198.51.100.1");
        insertEvent(bea, "2030-07-02T10:00:00Z", "OK", "opened", "198.51.100.2");
        insertEvent(ana, "2030-07-03T10:00:00Z", "ERROR", "night-mode", "2001:db8::3");
        insertEvent(carlos, "2030-07-04T10:00:00Z", "OK", "opened", "198.51.100.4");

        assertThat(body(history(casa, ""))).containsSubsequence(
                        "3/7/2030 12:00:00", "Ana", "Error: modo nocturno", "2001:db8::3",
                        "2/7/2030 12:00:00", "Bea", "Abierta", "198.51.100.2",
                        "1/7/2030 12:00:00", "Ana", "Denegado: todavía no válido", "198.51.100.1")
                .as("another lock's attempts").doesNotContain("Carlos", "198.51.100.4")
                .doesNotContain("Anteriores", "Más recientes");
    }

    @Test
    void linkHistoryShowsOnlyThatLinksAttempts() {
        insertEvent(ana, "2030-07-01T10:00:00Z", "OK", "opened", "198.51.100.1");
        insertEvent(bea, "2030-07-02T10:00:00Z", "OK", "opened", "198.51.100.2");

        assertThat(body(history(casa, "?link=" + ana))).contains("Solo el enlace de Ana", "198.51.100.1")
                .doesNotContain("198.51.100.2");
    }

    @Test
    void historyComesInPagesOfFifty() {
        migrator.sql("""
                        INSERT INTO access_event (owner_id, link_id, lock_id, occurred_at, result, reason, ip)
                        SELECT owner_id, id, lock_id, timestamptz '2030-07-01T10:00:00Z' + n * interval '1 minute',
                               'OK', 'opened', ('10.0.0.' || n)::inet
                        FROM access_link, generate_series(1, 51) AS n WHERE id = ?
                        """)
                .param(ana).update();

        var first = body(history(casa, "?link=" + ana));
        assertThat(first).contains(">10.0.0.51<", ">10.0.0.2<").doesNotContain(">10.0.0.1<", "Más recientes")
                .contains("Anteriores");
        var older = first.replaceAll("(?s).*href=\"([^\"]*)\">Anteriores.*", "$1").replace("&amp;", "&");
        assertThat(older).startsWith("/locks/" + casa + "/history?link=" + ana + "&before=");

        var second = body(mvc.get().uri(older).with(owner(ALICE)).exchange());
        assertThat(second).contains(">10.0.0.1<", "Más recientes").doesNotContain(">10.0.0.2<", "Anteriores");
    }

    @Test
    void unknownReasonsShowAsRecorded() {
        insertEvent(ana, "2030-07-01T10:00:00Z", "DENIED", "some-new-reason", "198.51.100.1");

        assertThat(body(history(casa, ""))).contains("some-new-reason");
    }

    @Test
    void lockWithoutAttemptsSaysSo() {
        assertThat(body(history(casa, ""))).contains("Todavía no hay intentos de abrir");
    }

    @Test
    void anotherOwnersLockOrALinkOfAnotherLockIsNotFound() {
        assertThat(history(bobsLock, "")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(history(casa, "?link=" + bobsLink)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(history(casa, "?link=" + carlos)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(history(casa, "?link=" + UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void anotherOwnersAttemptsNeverShow() {
        insertEvent(bobsLink, "2030-07-01T10:00:00Z", "OK", "opened", "198.51.100.9");

        assertThat(body(history(casa, ""))).doesNotContain("198.51.100.9");
    }

    @Test
    void malformedParametersAreBadRequests() {
        assertThat(history(casa, "?before=yesterday")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(history(casa, "?link=ana")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void lockAndLinkPagesLeadToTheHistory() {
        assertThat(body(mvc.get().uri("/locks/" + casa).with(owner(ALICE)).exchange()))
                .contains("href=\"/locks/" + casa + "/history\"");
        assertThat(body(mvc.get().uri("/locks/" + casa + "/links").with(owner(ALICE)).exchange()))
                .contains("href=\"/locks/" + casa + "/history?link=" + ana + "\"");
    }

    private MvcTestResult history(UUID lockId, String query) {
        return mvc.get().uri("/locks/" + lockId + "/history" + query).with(owner(ALICE)).exchange();
    }

    private static String body(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void insertEvent(UUID linkId, String occurredAt, String result, String reason, String ip) {
        migrator.sql("""
                        INSERT INTO access_event (owner_id, link_id, lock_id, occurred_at, result, reason, ip)
                        SELECT owner_id, id, lock_id, CAST(? AS timestamptz), ?, ?, CAST(? AS inet)
                        FROM access_link WHERE id = ?
                        """)
                .params(occurredAt, result, reason, ip, linkId)
                .update();
    }

    private UUID insertOwner(String email) {
        return migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id").param(email).query(UUID.class).single();
    }

    private UUID insertLock(UUID ownerId, long nukiSmartlockId, String name, String timeZone) {
        return migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone) VALUES (?, ?, ?, ?) RETURNING id
                        """)
                .params(ownerId, nukiSmartlockId, name, timeZone)
                .query(UUID.class)
                .single();
    }

    private UUID insertLink(UUID lockId, String label) {
        return migrator.sql("""
                        INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until)
                        SELECT owner_id, id, ?, now(), now() + interval '1 day' FROM lock WHERE id = ? RETURNING id
                        """)
                .params(label, lockId)
                .query(UUID.class)
                .single();
    }

    private static RequestPostProcessor owner(String email) {
        return user(email).roles("OWNER");
    }
}
