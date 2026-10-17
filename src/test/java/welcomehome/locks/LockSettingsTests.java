package welcomehome.locks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

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

/** Same configuration as LockSyncTests, so both share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class LockSettingsTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID alicesLock;
    UUID bobsLock;

    @BeforeEach
    void insertALockForAliceAndBob() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        alicesLock = insertLock(ALICE, "Casa");
        bobsLock = insertLock(BOB, "Apartamento de Bob");
    }

    @Test
    void editPageShowsTheCurrentSettings() {
        assertThat(mvc.get().uri("/locks/" + alicesLock).with(owner(ALICE)))
                .hasStatusOk()
                .bodyText().contains("Casa", "Europe/Berlin", "Guardar");
        assertThat(mvc.get().uri("/locks").with(owner(ALICE))).bodyText().contains("Editar");
    }

    @Test
    void ownerRenamesALockAndChangesItsTimeZone() {
        var result = update(ALICE, alicesLock, "  Casa de la playa ", "Europe/Madrid");

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/locks");
        assertThat(settings(alicesLock)).isEqualTo("Casa de la playa|Europe/Madrid");
        assertThat(mvc.get().uri("/locks").with(owner(ALICE)).flashAttr("updated", "Casa de la playa"))
                .bodyText().contains("Guardados los cambios de Casa de la playa", "Europe/Madrid");
    }

    @Test
    void blankOrLongNamesAreRejected() {
        for (var name : new String[] {"", "   ", "x".repeat(101)}) {
            assertThat(update(ALICE, alicesLock, name, "Europe/Madrid"))
                    .as(name)
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyText().contains("Escribe un nombre de hasta 100 caracteres");
        }
        assertThat(settings(alicesLock)).isEqualTo("Casa|Europe/Berlin");
    }

    @Test
    void onlyRegionTimeZonesAreAccepted() {
        for (var timeZone : new String[] {"", "Mars/Olympus_Mons", "+01:00", "CET", "europe/madrid"}) {
            assertThat(update(ALICE, alicesLock, "Casa", timeZone))
                    .as(timeZone)
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyText().contains("Elige una zona horaria de la lista");
        }
        assertThat(settings(alicesLock)).isEqualTo("Casa|Europe/Berlin");
        assertThat(update(ALICE, alicesLock, "Casa", "UTC")).hasStatus(HttpStatus.FOUND);
    }

    @Test
    void anotherOwnersLockIsNotFound() {
        assertThat(mvc.get().uri("/locks/" + bobsLock).with(owner(ALICE))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(update(ALICE, bobsLock, "Mía", "Europe/Madrid")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(settings(bobsLock)).isEqualTo("Apartamento de Bob|Europe/Berlin");
    }

    @Test
    void htmlFormSendsThePatchAsAPostWithHiddenMethod() {
        assertThat(mvc.post().uri("/locks/" + alicesLock).with(owner(ALICE)).with(csrf())
                .param("_method", "patch").param("name", "Casa").param("timeZone", "Europe/Madrid"))
                .hasStatus(HttpStatus.FOUND);
        assertThat(settings(alicesLock)).isEqualTo("Casa|Europe/Madrid");
    }

    @Test
    void updatingRequiresTheCsrfToken() {
        assertThat(mvc.patch().uri("/locks/" + alicesLock).with(owner(ALICE))
                .param("name", "Otra").param("timeZone", "Europe/Madrid"))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(settings(alicesLock)).isEqualTo("Casa|Europe/Berlin");
    }

    private MvcTestResult update(String email, UUID lockId, String name, String timeZone) {
        return mvc.patch().uri("/locks/" + lockId).with(owner(email)).with(csrf())
                .param("name", name).param("timeZone", timeZone)
                .exchange();
    }

    private String settings(UUID lockId) {
        return migrator.sql("SELECT name || '|' || time_zone FROM lock WHERE id = ?")
                .param(lockId).query(String.class).single();
    }

    private UUID insertLock(String email, String name) {
        var ownerId = migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id")
                .param(email).query(UUID.class).single();
        return migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, 17, ?, 'Europe/Berlin')
                        RETURNING id
                        """)
                .params(ownerId, name)
                .query(UUID.class)
                .single();
    }

    private static RequestPostProcessor owner(String email) {
        return user(email).roles("OWNER");
    }
}
