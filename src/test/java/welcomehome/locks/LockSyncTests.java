package welcomehome.locks;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import welcomehome.locks.LockSync.SyncResult;
import welcomehome.nuki.NukiStandInConfiguration;

@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class LockSyncTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";
    static final String CASA_AND_GARAJE = """
            [
              {"smartlockId": 17, "type": 5, "name": "Casa", "serverState": 0, "config": {"timezoneId": 40}},
              {"smartlockId": 18, "type": 4, "name": "Garaje", "serverState": 0}
            ]
            """;
    static final String ONLY_CASA = """
            [{"smartlockId": 17, "type": 5, "name": "Casa (Nuki)", "serverState": 0}]
            """;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    WireMockServer nuki;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID aliceId;

    @BeforeEach
    void insertAliceAndBob() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        aliceId = insertOwner(ALICE);
        insertOwner(BOB);
    }

    @Test
    void firstSyncImportsTheLocksWithTheirNukiNamesAndTimeZones() {
        connectNuki(ALICE, CASA_AND_GARAJE);

        var result = sync(ALICE);

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/locks");
        assertThat(result.getMvcResult().getFlashMap().get("synced")).isEqualTo(new SyncResult(2, 0));
        assertThat(locks()).as("Garaje reports no time zone, so it gets the server's").containsExactly(
                "17|Casa|Europe/London|true|connected",
                "18|Garaje|" + ZoneId.systemDefault().getId() + "|true|connected");
        assertThat(mvc.get().uri("/locks").with(owner(ALICE))).bodyText().contains("Casa", "Garaje");
    }

    @Test
    void openersAreImportedAsOpeners() {
        connectNuki(ALICE, """
                [
                  {"smartlockId": 17, "type": 5, "name": "Casa", "serverState": 0},
                  {"smartlockId": 19, "type": 2, "name": "Portal", "serverState": 0}
                ]
                """);

        sync(ALICE);

        assertThat(migrator.sql("SELECT nuki_smartlock_id || '|' || device_type FROM lock ORDER BY nuki_smartlock_id")
                .query(String.class).list()).containsExactly("17|SMART_LOCK", "19|OPENER");
        assertThat(mvc.get().uri("/locks").with(owner(ALICE))).bodyText().contains("Portal", "Nuki Opener");
    }

    @Test
    void laterSyncsKeepTheOwnersChoicesAndDeactivateLocksThatAreGone() {
        connectNuki(ALICE, CASA_AND_GARAJE);
        sync(ALICE);
        migrator.sql("UPDATE lock SET name = 'Mi casa', time_zone = 'Atlantic/Canary' WHERE nuki_smartlock_id = 17")
                .update();
        insertLinkFor(18);
        nukiLists(ONLY_CASA);

        var result = sync(ALICE);

        assertThat(result.getMvcResult().getFlashMap().get("synced")).isEqualTo(new SyncResult(0, 1));
        assertThat(locks()).containsExactly(
                "17|Mi casa|Atlantic/Canary|true|connected",
                "18|Garaje|" + ZoneId.systemDefault().getId() + "|false|connected");
        assertThat(migrator.sql("SELECT count(*) FROM access_link").query(Long.class).single()).isEqualTo(1);
        assertThat(mvc.get().uri("/locks").with(owner(ALICE))).bodyText().contains("Ya no está en tu cuenta de Nuki");
    }

    @Test
    void lockThatComesBackIsActiveAgain() {
        connectNuki(ALICE, CASA_AND_GARAJE);
        sync(ALICE);
        nukiLists(ONLY_CASA);
        sync(ALICE);
        nukiLists(CASA_AND_GARAJE);

        var result = sync(ALICE);

        assertThat(result.getMvcResult().getFlashMap().get("synced")).isEqualTo(new SyncResult(0, 0));
        assertThat(migrator.sql("SELECT bool_and(active) FROM lock").query(Boolean.class).single()).isTrue();
    }

    @Test
    void syncAfterReconnectingLinksTheLocksToTheNewConnection() {
        connectNuki(ALICE, CASA_AND_GARAJE);
        sync(ALICE);
        mvc.delete().uri("/settings/nuki").with(owner(ALICE)).with(csrf()).exchange();
        assertThat(migrator.sql("SELECT bool_and(connection_id IS NULL) FROM lock").query(Boolean.class).single())
                .isTrue();

        connectNuki(ALICE, CASA_AND_GARAJE);
        sync(ALICE);

        assertThat(locks()).allMatch(lock -> lock.endsWith("|connected"));
    }

    @Test
    void syncWithoutNukiAsksToConnectFirst() {
        var result = sync(ALICE);

        assertThat(result.getMvcResult().getFlashMap().get("syncError")).isEqualTo("not-connected");
        nuki.verify(0, anyRequestedFor(anyUrl()));
        assertThat(mvc.get().uri("/locks").with(owner(ALICE)).flashAttr("syncError", "not-connected"))
                .bodyText().contains("conecta tu cuenta de Nuki");
    }

    @Test
    void syncThatNukiRejectsChangesNoLock() {
        connectNuki(ALICE, CASA_AND_GARAJE);
        sync(ALICE);
        nuki.resetMappings();
        nuki.stubFor(get("/smartlock").willReturn(status(401)));

        var result = sync(ALICE);

        assertThat(result.getMvcResult().getFlashMap().get("syncError")).isEqualTo("rejected");
        assertThat(migrator.sql("SELECT bool_and(active) FROM lock").query(Boolean.class).single()).isTrue();
        assertThat(migrator.sql("SELECT status FROM nuki_connection").query(String.class).single())
                .isEqualTo("DISCONNECTED");
    }

    @Test
    void ownersSeeAndSyncOnlyTheirOwnLocks() {
        connectNuki(BOB, ONLY_CASA);
        sync(BOB);

        assertThat(mvc.get().uri("/locks").with(owner(ALICE))).bodyText()
                .contains("Todavía no tienes cerraduras", "<a href=\"/settings/nuki#nuki-web\">actívalas en Nuki Web</a>")
                .doesNotContain("Casa (Nuki)");
        connectNuki(ALICE, CASA_AND_GARAJE);
        sync(ALICE);
        assertThat(migrator.sql("SELECT count(*) FROM lock l JOIN owner o ON o.id = l.owner_id WHERE o.email = ?")
                .param(BOB).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void syncRequiresTheCsrfToken() {
        assertThat(mvc.post().uri("/locks/sync").with(owner(ALICE))).hasStatus(HttpStatus.FORBIDDEN);
    }

    private void connectNuki(String email, String locksJson) {
        nukiLists(locksJson);
        NukiStandInConfiguration.logIsReadable(nuki);
        assertThat(mvc.put().uri("/settings/nuki").with(owner(email)).with(csrf()).param("apiToken", "token-" + email))
                .hasStatus(HttpStatus.FOUND);
    }

    private void nukiLists(String locksJson) {
        nuki.resetMappings();
        nuki.stubFor(get("/smartlock").willReturn(okJson(locksJson)));
    }

    private MvcTestResult sync(String email) {
        return mvc.post().uri("/locks/sync").with(owner(email)).with(csrf()).exchange();
    }

    /** Alice's locks as "nukiId|name|timeZone|active|connected", where connected means her current connection. */
    private List<String> locks() {
        return migrator.sql("""
                        SELECT concat_ws('|', l.nuki_smartlock_id, l.name, l.time_zone, CASE WHEN l.active THEN 'true' ELSE 'false' END,
                                         CASE WHEN l.connection_id = c.id THEN 'connected' ELSE 'not connected' END)
                        FROM lock l LEFT JOIN nuki_connection c ON c.owner_id = l.owner_id
                        WHERE l.owner_id = ?
                        ORDER BY l.nuki_smartlock_id
                        """)
                .param(aliceId)
                .query(String.class)
                .list();
    }

    private void insertLinkFor(long nukiSmartlockId) {
        migrator.sql("""
                        INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until)
                        SELECT owner_id, id, 'Invitado', now(), now() + interval '1 day' FROM lock
                        WHERE owner_id = ? AND nuki_smartlock_id = ?
                        """)
                .params(aliceId, nukiSmartlockId)
                .update();
    }

    private UUID insertOwner(String email) {
        return migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id").param(email).query(UUID.class).single();
    }

    private static RequestPostProcessor owner(String email) {
        return user(email).roles("OWNER");
    }
}
