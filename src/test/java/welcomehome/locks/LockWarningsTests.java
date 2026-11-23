package welcomehome.locks;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.SignedIn;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as LockSyncTests, so both share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class LockWarningsTests {

    static final String ALICE = "alice@example.com";
    static final String OFFLINE = "Nuki indica que la cerradura no tiene conexión";
    static final String BATTERY = "Batería crítica";
    static final String MOTOR = "Motor bloqueado";
    static final String UNKNOWN = "no podemos avisarte";
    static final String ALL_WELL = """
            {"smartlockId": 17, "type": 5, "name": "Casa", "serverState": 0,
             "state": {"state": 1, "batteryCritical": false, "batteryCharge": 80},
             "advancedConfig": {"autoLock": true}}
            """;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    WireMockServer nuki;

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    LockReachability reachability;

    @Autowired
    LockProblems problems;

    JdbcClient migrator;
    TestData data;
    UUID aliceId;

    static final String UNCHECKED = "No comprueba dónde están los invitados: sus enlaces abren la puerta desde cualquier lugar.";

    @BeforeEach
    void insertAliceWithALock() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        data.deleteOwners();
        aliceId = data.owner(ALICE);
        insertLock(17, "Casa", "SMART_LOCK");
    }

    @Test
    void listsSayWhatIsWrongWithALock() {
        connectNuki();
        nukiLists("""
                {"smartlockId": 17, "type": 5, "name": "Casa", "serverState": 4,
                 "state": {"state": 254, "batteryCritical": true, "batteryCharge": 5},
                 "advancedConfig": {"autoLock": false}}
                """);

        assertThat(body("/")).contains(OFFLINE, BATTERY, MOTOR).doesNotContain(UNKNOWN);
    }

    @Test
    void aLockWithoutProblemsHasNoWarnings() {
        connectNuki();
        nukiLists(ALL_WELL);

        assertThat(body("/")).doesNotContain(OFFLINE, BATTERY, MOTOR, UNKNOWN);
    }

    @Test
    void aLockReportedOfflineThatHasOpenedSinceIsNotWarnedAboutUntilAnOpeningFails() {
        var casa = migrator.sql("SELECT id FROM lock WHERE nuki_smartlock_id = 17").query(UUID.class).single();
        connectNuki();
        nukiLists(ALL_WELL.replace("\"serverState\": 0", "\"serverState\": 4"));

        reachability.opened(aliceId, casa);
        assertThat(body("/")).as("an opening before Nuki's report was seen tells nothing").contains(OFFLINE);

        problems.check(aliceId);
        reachability.opened(aliceId, casa);
        assertThat(body("/")).doesNotContain(OFFLINE);
        assertThat(body("/")).doesNotContain(OFFLINE);

        reachability.failedToOpen(aliceId, casa);
        assertThat(body("/")).contains(OFFLINE);
    }

    @Test
    void anOpeningDisprovesOnlyTheOfflineReportItCameAfter() {
        var casa = migrator.sql("SELECT id FROM lock WHERE nuki_smartlock_id = 17").query(UUID.class).single();
        connectNuki();
        var offline = ALL_WELL.replace("\"serverState\": 0", "\"serverState\": 4");
        nukiLists(offline);
        problems.check(aliceId);
        assertThat(body("/")).contains(OFFLINE);
        reachability.opened(aliceId, casa);
        assertThat(body("/")).doesNotContain(OFFLINE);

        nukiLists(ALL_WELL);
        problems.check(aliceId);
        assertThat(body("/")).doesNotContain(OFFLINE);

        nukiLists(offline);
        problems.check(aliceId);
        assertThat(body("/")).as("Nuki reports it offline anew").contains(OFFLINE);
    }

    /** When a report began would otherwise depend on who looked at a page. */
    @Test
    void pagesNeitherStartNorEndAnOfflineReport() {
        connectNuki();
        nukiLists(ALL_WELL.replace("\"serverState\": 0", "\"serverState\": 4"));
        assertThat(body("/")).contains(OFFLINE);
        assertThat(body("/")).contains(OFFLINE);
        assertThat(offlineReport()).isEqualTo("none");

        data.offlineSince("now() - interval '1 hour'");
        var report = offlineReport();
        nukiLists(ALL_WELL);
        assertThat(body("/")).doesNotContain(OFFLINE);
        assertThat(body("/")).doesNotContain(OFFLINE);
        assertThat(offlineReport()).isEqualTo(report);
    }

    private String offlineReport() {
        return migrator.sql("SELECT coalesce(offline_since::text, 'none') FROM lock WHERE nuki_smartlock_id = 17")
                .query(String.class).single();
    }

    @Test
    void eachWarningGoesWithItsLockAndAllAreReadInOneRequest() {
        insertLock(18, "Garaje", "SMART_LOCK");
        connectNuki();
        nuki.resetRequests();
        nukiLists(ALL_WELL + "," + """
                {"smartlockId": 18, "type": 5, "name": "Garaje", "serverState": 0,
                 "state": {"state": 1, "batteryCritical": true}, "advancedConfig": {"autoLock": true}}
                """);

        assertThat(body("/")).containsSubsequence("Casa", "Garaje", BATTERY);
        nuki.verify(1, getRequestedFor(urlEqualTo("/smartlock")));
    }

    /** Whether a lock locks itself again is its owner's choice in Nuki, and no problem of the lock. */
    @Test
    void autoLockOffIsNoWarning() {
        connectNuki();
        nukiLists(ALL_WELL.replace("\"autoLock\": true", "\"autoLock\": false"));

        assertThat(body("/")).doesNotContain("Auto Lock");
    }

    /** Not Nuki's to tell, so it is said with or without a connection: this lock's links open from anywhere. */
    @Test
    void aLockThatDoesNotCheckWhereItsGuestsAreSaysSoUntilItDoes() {
        var lockId = migrator.sql("SELECT id FROM lock").query(UUID.class).single();
        assertThat(body("/")).contains(UNCHECKED, ">Activar la comprobación</a>");

        data.locationCheck(lockId, 40.416775, -3.703790, "OFF", 150);
        assertThat(body("/")).as("a position alone checks nothing").contains(UNCHECKED);

        data.locationCheck(lockId, 40.416775, -3.703790, "CONFIRM_WHEN_FAR", 150);
        assertThat(body("/")).doesNotContain(UNCHECKED);
    }

    @Test
    void anInactiveLockHasNoWarningsNorIsNukiAskedForItsState() {
        data.deactivateLocks();
        connectNuki();
        nuki.resetRequests();

        assertThat(body("/")).doesNotContain(UNKNOWN, UNCHECKED);
        assertThat(body("/")).doesNotContain(UNCHECKED);
        nuki.resetRequests();
        assertThat(body("/")).doesNotContain(UNKNOWN);
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void withoutAConnectionNukiIsNotAskedAndNothingIsUnknown() {
        assertThat(body("/")).contains("Conecta tu cuenta de Nuki").doesNotContain(UNKNOWN);
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void pagesStillWorkWhenNukiFailsAndSaySoInsteadOfLookingAllWell() {
        connectNuki();
        nuki.stubFor(get("/smartlock").willReturn(status(503)));

        assertThat(body("/")).contains("Casa", UNKNOWN);
    }

    @Test
    void aSlowNukiDoesNotKeepTheOwnerWaiting() {
        connectNuki();
        nuki.stubFor(get("/smartlock").willReturn(okJson("[" + ALL_WELL + "]").withFixedDelay(5_000)));

        var start = System.nanoTime();
        var home = body("/");

        assertThat(home).contains("Casa", UNKNOWN);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(4_000);
    }

    @Test
    void homePageSaysAtOnceThatNukiHasRejectedTheConnection() {
        connectNuki();
        nuki.stubFor(get("/smartlock").willReturn(status(401)));

        assertThat(body("/")).contains("Volver a conectar").doesNotContain(UNKNOWN);
    }

    private void connectNuki() {
        nuki.stubFor(get("/smartlock").willReturn(okJson("[]")));
        NukiStandInConfiguration.logIsReadable(nuki);
        NukiStandInConfiguration.connect(mvc, nuki, ALICE, "alices-token");
    }

    private void nukiLists(String locks) {
        nuki.stubFor(get("/smartlock").willReturn(okJson("[" + locks + "]")));
    }

    private void insertLock(long nukiSmartlockId, String name, String deviceType) {
        if ("OPENER".equals(deviceType)) {
            data.opener(aliceId, nukiSmartlockId, name);
        } else {
            data.lock(aliceId, nukiSmartlockId, name);
        }
    }

    private String body(String page) {
        var result = mvc.get().uri(page).with(owner()).exchange();
        assertThat(result).hasStatusOk();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static RequestPostProcessor owner() {
        return SignedIn.owner(ALICE);
    }
}
