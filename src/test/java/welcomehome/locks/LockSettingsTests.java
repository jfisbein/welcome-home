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
import welcomehome.TestData;
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
    TestData data;
    UUID alicesLock;
    UUID bobsLock;

    @BeforeEach
    void insertALockForAliceAndBob() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        migrator.sql("DELETE FROM owner").update();
        alicesLock = insertLock(ALICE, "Casa");
        bobsLock = insertLock(BOB, "Apartamento de Bob");
    }

    @Test
    void editPageShowsTheCurrentSettings() {
        assertThat(mvc.get().uri("/locks/" + alicesLock).with(owner(ALICE)))
                .hasStatusOk()
                .bodyText().contains("Casa", "Europe/Berlin (hora de Europa central)", "Guardar");
        assertThat(mvc.get().uri("/locks").with(owner(ALICE))).bodyText().contains("Estado y ajustes");
    }

    @Test
    void ownerRenamesALockAndChangesItsTimeZone() {
        // London, not Madrid: the list names zones, and Madrid's name is Berlin's.
        var result = update(ALICE, alicesLock, "  Casa de la playa ", "Europe/London");

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/locks");
        assertThat(settings(alicesLock)).isEqualTo("Casa de la playa|Europe/London");
        assertThat(mvc.get().uri("/locks").with(owner(ALICE)).flashAttr("updated", "Casa de la playa"))
                .bodyText().contains("Guardados los cambios de Casa de la playa", "hora de Londres");
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
    void ownerWritesInstructionsForTheLocksGuests() {
        assertThat(mvc.get().uri("/locks/" + alicesLock).with(owner(ALICE))).bodyText()
                .contains("Instrucciones para tus invitados", "maxlength=\"1000\"></textarea>");

        assertThat(updateInstructions(alicesLock, "  Calle Mayor 1, 3.º B.\r\nLa llave está en el buzón.\r\n "))
                .hasStatus(HttpStatus.FOUND);

        assertThat(guestInstructions(alicesLock)).isEqualTo("Calle Mayor 1, 3.º B.\nLa llave está en el buzón.");
        assertThat(mvc.get().uri("/locks/" + alicesLock).with(owner(ALICE))).bodyText()
                .contains(">Calle Mayor 1, 3.º B.\nLa llave está en el buzón.</textarea>");
    }

    @Test
    void blankInstructionsAreNone() {
        data.guestInstructions(alicesLock, "Calle Mayor 1");

        assertThat(updateInstructions(alicesLock, " \r\n ")).hasStatus(HttpStatus.FOUND);

        assertThat(guestInstructions(alicesLock)).isEqualTo("none");
    }

    /** A browser sends each line break as two characters, which count as one, as the owner sees one. */
    @Test
    void instructionsLongerThanTheLimitAreRejectedAndKeptInTheForm() {
        data.guestInstructions(alicesLock, "Calle Mayor 1");
        var longest = "x".repeat(998) + "\r\ny";

        assertThat(updateInstructions(alicesLock, longest + "z"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyText().contains("Las instrucciones pueden tener hasta 1000 caracteres.", "z</textarea>");
        assertThat(guestInstructions(alicesLock)).isEqualTo("Calle Mayor 1");

        assertThat(updateInstructions(alicesLock, longest)).hasStatus(HttpStatus.FOUND);
        assertThat(guestInstructions(alicesLock)).isEqualTo("x".repeat(998) + "\ny");
    }

    /** As Google Maps copies a place, or with spaces between, rounded to six decimals. */
    @Test
    void ownerSetsWhereTheLockIs() {
        assertThat(updatePosition(alicesLock, " 40.4167754, -3.7037902 ")).hasStatus(HttpStatus.FOUND);
        assertThat(position(alicesLock)).isEqualTo("40.416775|-3.70379");

        assertThat(mvc.get().uri("/locks/" + alicesLock).with(owner(ALICE))).bodyText().contains(
                "value=\"40.416775, -3.703790\"", "Ubicación de la cerradura", "Usar mi ubicación actual",
                "href=\"https://www.openstreetmap.org/?mlat=40.416775&amp;mlon=-3.703790#map=18/40.416775/-3.703790\"",
                "/js/location.js");

        assertThat(updatePosition(alicesLock, "41.38879 2.15899")).hasStatus(HttpStatus.FOUND);
        assertThat(position(alicesLock)).isEqualTo("41.38879|2.15899");
    }

    @Test
    void aBlankPositionIsNoneAndAFormWithoutItKeepsTheLocks() {
        updatePosition(alicesLock, "40.416775, -3.70379");

        assertThat(update(ALICE, alicesLock, "Casa", "Europe/Madrid")).as("no field").hasStatus(HttpStatus.FOUND);
        assertThat(position(alicesLock)).isEqualTo("40.416775|-3.70379");

        assertThat(updatePosition(alicesLock, "  ")).hasStatus(HttpStatus.FOUND);
        assertThat(position(alicesLock)).isEqualTo("none");
        assertThat(mvc.get().uri("/locks/" + alicesLock).with(owner(ALICE))).bodyText()
                .contains("name=\"coordinates\" autocomplete=\"off\" value=\"\"").doesNotContain("openstreetmap");
    }

    @Test
    void aPositionThatIsNoPlaceOnEarthIsRefusedAndKeptInTheForm() {
        updatePosition(alicesLock, "40.416775, -3.70379");

        for (var typed : new String[] {"40.4", "40,4168", "91, 0", "40.4, 181", "40.4; -3.7", "norte", "1e3, 2"}) {
            assertThat(updatePosition(alicesLock, typed)).as(typed)
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyText().contains("Escribe la latitud y la longitud con punto decimal",
                            "value=\"" + typed + "\"");
        }
        assertThat(position(alicesLock)).isEqualTo("40.416775|-3.70379");
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

    private MvcTestResult updatePosition(UUID lockId, String coordinates) {
        return mvc.patch().uri("/locks/" + lockId).with(owner(ALICE)).with(csrf())
                .param("name", "Casa").param("timeZone", "Europe/Berlin").param("coordinates", coordinates)
                .exchange();
    }

    /** The lock's position as "latitude|longitude", or "none". */
    private String position(UUID lockId) {
        return migrator.sql("SELECT coalesce(latitude || '|' || longitude, 'none') FROM lock WHERE id = ?")
                .param(lockId).query(String.class).single();
    }

    private MvcTestResult updateEntrance(UUID lockId, String entranceLockId) {
        return mvc.patch().uri("/locks/" + lockId).with(owner(ALICE)).with(csrf())
                .param("name", "Casa").param("timeZone", "Europe/Berlin").param("entranceLockId", entranceLockId)
                .exchange();
    }

    private MvcTestResult updateInstructions(UUID lockId, String guestInstructions) {
        return mvc.patch().uri("/locks/" + lockId).with(owner(ALICE)).with(csrf())
                .param("name", "Casa").param("timeZone", "Europe/Berlin").param("guestInstructions", guestInstructions)
                .exchange();
    }

    private String guestInstructions(UUID lockId) {
        return migrator.sql("SELECT coalesce(guest_instructions, 'none') FROM lock WHERE id = ?")
                .param(lockId).query(String.class).single();
    }

    private String entrance(UUID lockId) {
        return migrator.sql("SELECT coalesce(entrance_lock_id::text, 'none') FROM lock WHERE id = ?")
                .param(lockId).query(String.class).single();
    }

    private UUID ownerOf(UUID lockId) {
        return migrator.sql("SELECT owner_id FROM lock WHERE id = ?").param(lockId).query(UUID.class).single();
    }

    private UUID insertLock(UUID ownerId, long nukiSmartlockId, String name, String deviceType) {
        return "OPENER".equals(deviceType) ? data.opener(ownerId, nukiSmartlockId, name, "Europe/Berlin")
                : data.lock(ownerId, nukiSmartlockId, name, "Europe/Berlin");
    }

    private String settings(UUID lockId) {
        return migrator.sql("SELECT name || '|' || time_zone FROM lock WHERE id = ?")
                .param(lockId).query(String.class).single();
    }

    private UUID insertLock(String email, String name) {
        return data.lock(data.owner(email), 17, name, "Europe/Berlin");
    }

    private static RequestPostProcessor owner(String email) {
        return user(email).roles("OWNER");
    }
}
