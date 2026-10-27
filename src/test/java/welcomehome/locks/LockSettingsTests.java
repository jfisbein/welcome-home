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
    void ownerChoosesOneOfTheirOpenersAsTheLocksEntrance() {
        assertThat(mvc.get().uri("/locks/" + alicesLock).with(owner(ALICE))).as("without an Opener, no entrance")
                .bodyText().doesNotContain("Portal del edificio");
        var portal = insertLock(ownerOf(alicesLock), 19, "Portal", "OPENER");

        assertThat(mvc.get().uri("/locks/" + alicesLock).with(owner(ALICE)))
                .bodyText().contains("Portal del edificio", "Ninguno", "Portal");
        assertThat(updateEntrance(alicesLock, portal.toString())).hasStatus(HttpStatus.FOUND);
        assertThat(entrance(alicesLock)).isEqualTo(portal.toString());

        assertThat(updateEntrance(alicesLock, "")).hasStatus(HttpStatus.FOUND);
        assertThat(entrance(alicesLock)).isEqualTo("none");
    }

    /** Neither another owner's Opener, nor a smart lock, nor the lock itself; and an Opener has no entrance. */
    @Test
    void anEntranceIsOneOfTheOwnersOpeners() {
        var alice = ownerOf(alicesLock);
        var portal = insertLock(alice, 19, "Portal", "OPENER");
        var garaje = insertLock(alice, 18, "Garaje", "SMART_LOCK");
        var bobsPortal = insertLock(ownerOf(bobsLock), 19, "Portal de Bob", "OPENER");

        for (var entrance : new UUID[] {garaje, bobsPortal, alicesLock, UUID.randomUUID()}) {
            assertThat(updateEntrance(alicesLock, entrance.toString())).as(entrance.toString())
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyText().contains("Elige uno de tus Nuki Opener de la lista");
        }
        assertThat(updateEntrance(portal, portal.toString())).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(entrance(alicesLock)).isEqualTo("none");
        assertThat(entrance(portal)).isEqualTo("none");
    }

    @Test
    void anOpenersPageSaysWhatItIsWithoutAutoLockOrEntrance() {
        var portal = insertLock(ownerOf(alicesLock), 19, "Portal", "OPENER");

        assertThat(mvc.get().uri("/locks/" + portal).with(owner(ALICE))).hasStatusOk().bodyText()
                .contains("Es un Nuki Opener")
                .doesNotContain("Portal del edificio", "El bloqueo lo gestiono de otra forma");
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

    private MvcTestResult updateEntrance(UUID lockId, String entranceLockId) {
        return mvc.patch().uri("/locks/" + lockId).with(owner(ALICE)).with(csrf())
                .param("name", "Casa").param("timeZone", "Europe/Berlin").param("entranceLockId", entranceLockId)
                .exchange();
    }

    private String entrance(UUID lockId) {
        return migrator.sql("SELECT coalesce(entrance_lock_id::text, 'none') FROM lock WHERE id = ?")
                .param(lockId).query(String.class).single();
    }

    private UUID ownerOf(UUID lockId) {
        return migrator.sql("SELECT owner_id FROM lock WHERE id = ?").param(lockId).query(UUID.class).single();
    }

    private UUID insertLock(UUID ownerId, long nukiSmartlockId, String name, String deviceType) {
        return migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone, device_type)
                        VALUES (?, ?, ?, 'Europe/Berlin', ?)
                        RETURNING id
                        """)
                .params(ownerId, nukiSmartlockId, name, deviceType)
                .query(UUID.class)
                .single();
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
