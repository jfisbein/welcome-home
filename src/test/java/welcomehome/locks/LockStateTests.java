package welcomehome.locks;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

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
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as LockSyncTests, so both share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class LockStateTests {

    static final String ALICE = "alice@example.com";
    static final String AUTO_LOCK_WARNING = "Auto Lock está desactivado.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    WireMockServer nuki;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID lockId;

    @BeforeEach
    void insertAliceWithALock() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        var aliceId = migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id")
                .param(ALICE).query(UUID.class).single();
        lockId = migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, 17, 'Casa', 'Europe/Madrid')
                        RETURNING id
                        """)
                .param(aliceId).query(UUID.class).single();
    }

    @Test
    void showsPositionConnectivityBatteryAndAutoLock() {
        connectNuki();
        nukiReportsState("""
                {"serverState": 0, "state": {"state": 1, "batteryCritical": false, "batteryCharge": 80},
                 "advancedConfig": {"autoLock": true}}
                """);

        assertThat(lockPage()).hasStatusOk().bodyText()
                .contains("Bloqueada", "En línea", "80 %", "Activado")
                .doesNotContain(AUTO_LOCK_WARNING);
    }

    @Test
    void warnsThatAutoLockIsOff() {
        connectNuki();
        nukiReportsState("""
                {"serverState": 0, "state": {"state": 3, "batteryCritical": false}, "advancedConfig": {"autoLock": false}}
                """);

        assertThat(lockPage()).bodyText().contains("Desbloqueada", "Correcta", AUTO_LOCK_WARNING);
    }

    @Test
    void ownerWhoRelocksSomeOtherWayIsNotWarned() {
        connectNuki();
        nukiReportsState("""
                {"serverState": 0, "state": {"state": 3, "batteryCritical": false}, "advancedConfig": {"autoLock": false}}
                """);

        assertThat(mvc.patch().uri("/locks/" + lockId).with(owner()).with(csrf())
                .param("name", "Casa").param("timeZone", "Europe/Madrid").param("relockingHandledElsewhere", "true"))
                .hasStatus(HttpStatus.FOUND);

        assertThat(lockPage()).bodyText()
                .contains("el bloqueo lo gestionas de otra forma")
                .doesNotContain(AUTO_LOCK_WARNING);
        assertThat(mvc.patch().uri("/locks/" + lockId).with(owner()).with(csrf())
                .param("name", "Casa").param("timeZone", "Europe/Madrid"))
                .as("an unticked checkbox is not sent")
                .hasStatus(HttpStatus.FOUND);
        assertThat(lockPage()).bodyText().contains(AUTO_LOCK_WARNING);
    }

    @Test
    void warnsAboutAnOfflineLockAndACriticalBattery() {
        connectNuki();
        nukiReportsState("""
                {"serverState": 4, "state": {"state": 254, "batteryCritical": true, "batteryCharge": 5},
                 "advancedConfig": {"autoLock": true}}
                """);

        assertThat(lockPage()).bodyText().contains("Motor bloqueado", "Sin conexión con Nuki", "Batería crítica");
    }

    @Test
    void settingsStayAvailableWhenTheStateCannotBeRead() {
        assertThat(lockPage()).hasStatusOk().bodyText().contains("conecta tu cuenta de Nuki", "Guardar");

        connectNuki();
        nuki.stubFor(get("/smartlock/17").willReturn(status(503)));
        assertThat(lockPage()).hasStatusOk().bodyText().contains("Nuki no responde", "Guardar");

        nuki.stubFor(get("/smartlock/17").willReturn(status(404)));
        assertThat(lockPage()).hasStatusOk().bodyText().contains("Nuki no encuentra esta cerradura", "Guardar");
    }

    @Test
    void inactiveLockIsNotAskedForItsState() {
        connectNuki();
        migrator.sql("UPDATE lock SET active = false").update();
        nuki.resetRequests();

        assertThat(lockPage()).hasStatusOk().bodyText().contains("ya no está en tu cuenta de Nuki", "Guardar");
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    private void connectNuki() {
        nuki.stubFor(get("/smartlock").willReturn(okJson("[]")));
        assertThat(mvc.put().uri("/nuki/connection").with(owner()).with(csrf()).param("apiToken", "alices-token"))
                .hasStatus(HttpStatus.FOUND);
    }

    private void nukiReportsState(String json) {
        nuki.stubFor(get("/smartlock/17").willReturn(okJson(json.replace("{\"serverState\"",
                "{\"smartlockId\": 17, \"type\": 5, \"name\": \"Casa\", \"serverState\""))));
    }

    private MvcTestResult lockPage() {
        return mvc.get().uri("/locks/" + lockId).with(owner()).exchange();
    }

    private static RequestPostProcessor owner() {
        return user(ALICE).roles("OWNER");
    }
}
