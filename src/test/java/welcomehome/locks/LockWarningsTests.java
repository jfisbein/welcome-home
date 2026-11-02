package welcomehome.locks;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as LockSyncTests, so both share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class LockWarningsTests {

    static final String ALICE = "alice@example.com";
    static final String OFFLINE = "Sin conexión con Nuki";
    static final String BATTERY = "Batería crítica";
    static final String MOTOR = "Motor bloqueado";
    static final String AUTO_LOCK = "Auto Lock desactivado";
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

    JdbcClient migrator;
    UUID aliceId;

    @BeforeEach
    void insertAliceWithALock() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        aliceId = migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id")
                .param(ALICE).query(UUID.class).single();
        insertLock(17, "Casa", "SMART_LOCK");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/locks"})
    void listsSayWhatIsWrongWithALock(String page) {
        connectNuki();
        nukiLists("""
                {"smartlockId": 17, "type": 5, "name": "Casa", "serverState": 4,
                 "state": {"state": 254, "batteryCritical": true, "batteryCharge": 5},
                 "advancedConfig": {"autoLock": false}}
                """);

        assertThat(body(page)).contains(OFFLINE, BATTERY, MOTOR, AUTO_LOCK).doesNotContain(UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/locks"})
    void aLockWithoutProblemsHasNoWarnings(String page) {
        connectNuki();
        nukiLists(ALL_WELL);

        assertThat(body(page)).doesNotContain(OFFLINE, BATTERY, MOTOR, AUTO_LOCK, UNKNOWN);
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

    @Test
    void autoLockOffIsNoWarningForAnOwnerWhoRelocksSomeOtherWay() {
        migrator.sql("UPDATE lock SET relocking_handled_elsewhere = true").update();
        connectNuki();
        nukiLists(ALL_WELL.replace("\"autoLock\": true", "\"autoLock\": false"));

        assertThat(body("/locks")).doesNotContain(AUTO_LOCK);
    }

    @Test
    void autoLockIsNoWarningForAnOpener() {
        migrator.sql("UPDATE lock SET device_type = 'OPENER'").update();
        connectNuki();
        nukiLists("""
                {"smartlockId": 17, "type": 2, "name": "Portal", "serverState": 0, "state": {"state": 1}}
                """);

        assertThat(body("/locks")).doesNotContain(AUTO_LOCK);
    }

    @Test
    void anInactiveLockHasNoWarningsNorIsNukiAskedForItsState() {
        migrator.sql("UPDATE lock SET active = false").update();
        connectNuki();
        nuki.resetRequests();

        assertThat(body("/")).doesNotContain(UNKNOWN);
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void withoutAConnectionNukiIsNotAskedAndNothingIsUnknown() {
        assertThat(body("/")).contains("Conecta tu cuenta de Nuki").doesNotContain(UNKNOWN);
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/locks"})
    void pagesStillWorkWhenNukiFailsAndSaySoInsteadOfLookingAllWell(String page) {
        connectNuki();
        nuki.stubFor(get("/smartlock").willReturn(status(503)));

        assertThat(body(page)).contains("Casa", UNKNOWN);
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
        assertThat(mvc.put().uri("/settings/nuki").with(owner()).with(csrf()).param("apiToken", "alices-token"))
                .hasStatus(HttpStatus.FOUND);
    }

    private void nukiLists(String locks) {
        nuki.stubFor(get("/smartlock").willReturn(okJson("[" + locks + "]")));
    }

    private void insertLock(long nukiSmartlockId, String name, String deviceType) {
        migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone, device_type)
                        VALUES (?, ?, ?, 'Europe/Madrid', ?)
                        """)
                .params(aliceId, nukiSmartlockId, name, deviceType).update();
    }

    private String body(String page) {
        var result = mvc.get().uri(page).with(owner()).exchange();
        assertThat(result).hasStatusOk();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static RequestPostProcessor owner() {
        return user(ALICE).roles("OWNER");
    }
}
