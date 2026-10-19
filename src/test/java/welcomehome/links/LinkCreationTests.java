package welcomehome.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
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
import welcomehome.crypto.LinkTokenSigner;
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as the locks tests, so they share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class LinkCreationTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    LinkTokenSigner signer;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID madridLock;
    UUID newYorkLock;
    UUID bobsLock;

    @BeforeEach
    void insertLocks() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        var alice = insertOwner(ALICE);
        madridLock = insertLock(alice, 17, "Casa", "Europe/Madrid");
        newYorkLock = insertLock(alice, 18, "Loft", "America/New_York");
        bobsLock = insertLock(insertOwner(BOB), 19, "Apartamento de Bob", "Europe/Madrid");
    }

    @Test
    void createdLinkHasASignedUrlForItsCurrentTokenVersion() {
        var result = create(madridLock, " Ana ", "2030-11-01T15:00", "2030-11-03T11:00");

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/locks/" + madridLock + "/links");
        var url = (URI) result.getMvcResult().getFlashMap().get("createdUrl");
        assertThat(url.toString()).startsWith(IntegrationTest.BASE_URL + "/o/");
        var token = url.getPath().substring("/o/".length());
        var linkId = signer.parseLinkId(token).orElseThrow();
        assertThat(signer.verify(token, 1)).isTrue();
        assertThat(migrator.sql("SELECT label || '|' || token_version FROM access_link WHERE id = ?")
                .param(linkId).query(String.class).single()).isEqualTo("Ana|1");
        assertThat(jdbc.sql("SELECT lock_name FROM resolve_link(?)").param(linkId).query(String.class).single())
                .as("the guest flow finds the link without an owner context")
                .isEqualTo("Casa");
    }

    @Test
    void databaseStoresNoToken() {
        var url = (URI) create(madridLock, "Ana", "2030-11-01T15:00", "2030-11-03T11:00")
                .getMvcResult().getFlashMap().get("createdUrl");
        var mac = url.getPath().substring(url.getPath().indexOf('.') + 1);

        var row = migrator.sql("SELECT access_link::text FROM access_link").query(String.class).single();

        assertThat(row).doesNotContain(mac);
        assertThat(migrator.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_name = 'access_link' AND (column_name LIKE '%token%' OR column_name LIKE '%mac%')
                        """)
                .query(String.class).list())
                .containsExactly("token_version");
    }

    @Test
    void datesAreEnteredInTheLocksTimeZone() {
        create(madridLock, "Madrid", "2030-11-01T15:00", "2030-11-03T11:00");
        create(newYorkLock, "New York", "2030-11-01T15:00", "2030-11-03T11:00");

        assertThat(validity("Madrid")).isEqualTo("2030-11-01T14:00:00Z|2030-11-03T10:00:00Z");
        assertThat(validity("New York")).isEqualTo("2030-11-01T19:00:00Z|2030-11-03T16:00:00Z");
    }

    @Test
    void pageShowsTheCreatedLinkToCopy() {
        var url = URI.create(IntegrationTest.BASE_URL + "/o/some-token");
        assertThat(mvc.get().uri("/locks/" + madridLock + "/links").with(owner(ALICE))
                .flashAttr("createdUrl", url).flashAttr("createdLabel", "Ana")
                .flashAttr("createdFrom", Instant.parse("2030-11-01T14:00:00Z").atZone(ZoneId.of("Europe/Madrid")))
                .flashAttr("createdUntil", Instant.parse("2030-11-03T10:00:00Z").atZone(ZoneId.of("Europe/Madrid"))))
                .hasStatusOk()
                .bodyText().contains("Enlace creado para Ana", "01/11/2030 15:00", "03/11/2030 11:00", url.toString(), "Copiar");
    }

    @Test
    void formIsPrefilledInTheLocksTimeZone() {
        assertThat(mvc.get().uri("/locks/" + newYorkLock + "/links").with(owner(ALICE)))
                .hasStatusOk()
                .bodyText().contains("Enlaces de Loft", "America/New_York", "Crear enlace");
    }

    @Test
    void invalidInputCreatesNoLink() {
        assertInvalid(create(madridLock, "  ", "2030-11-01T15:00", "2030-11-03T11:00"), "Escribe para quién es el enlace");
        assertInvalid(create(madridLock, "x".repeat(101), "2030-11-01T15:00", "2030-11-03T11:00"),
                "Escribe para quién es el enlace");
        assertInvalid(create(madridLock, "Ana", "mañana", "2030-11-03T11:00"), "Indica la fecha y hora de inicio");
        assertInvalid(create(madridLock, "Ana", "2030-11-01T15:00", ""), "Indica la fecha y hora de fin");
        assertInvalid(create(madridLock, "Ana", "2030-11-03T11:00", "2030-11-03T11:00"),
                "El fin tiene que ser posterior al inicio");
        assertInvalid(create(madridLock, "Ana", "2020-11-01T15:00", "2020-11-03T11:00"), "El fin ya ha pasado");
        assertThat(migrator.sql("SELECT count(*) FROM access_link").query(Long.class).single()).isZero();
    }

    @Test
    void linkCanStartInThePast() {
        assertThat(create(madridLock, "Ana", "2020-11-01T15:00", "2030-11-03T11:00")).hasStatus(HttpStatus.FOUND);
    }

    @Test
    void inactiveLockGetsNoNewLinks() {
        migrator.sql("UPDATE lock SET active = false WHERE id = ?").param(madridLock).update();

        assertThat(create(madridLock, "Ana", "2030-11-01T15:00", "2030-11-03T11:00"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyText().contains("no se pueden crear enlaces");
        assertThat(migrator.sql("SELECT count(*) FROM access_link").query(Long.class).single()).isZero();
    }

    @Test
    void anotherOwnersLockIsNotFound() {
        assertThat(mvc.get().uri("/locks/" + bobsLock + "/links").with(owner(ALICE))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(create(bobsLock, "Ana", "2030-11-01T15:00", "2030-11-03T11:00")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(migrator.sql("SELECT count(*) FROM access_link").query(Long.class).single()).isZero();
    }

    @Test
    void creatingRequiresTheCsrfToken() {
        assertThat(mvc.post().uri("/locks/" + madridLock + "/links").with(owner(ALICE))
                .param("label", "Ana").param("validFrom", "2030-11-01T15:00").param("validUntil", "2030-11-03T11:00"))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    private MvcTestResult create(UUID lockId, String label, String validFrom, String validUntil) {
        return mvc.post().uri("/locks/" + lockId + "/links").with(owner(ALICE)).with(csrf())
                .param("label", label).param("validFrom", validFrom).param("validUntil", validUntil)
                .exchange();
    }

    private static void assertInvalid(MvcTestResult result, String message) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).bodyText().contains(message);
    }

    private String validity(String label) {
        return migrator.sql("""
                        SELECT to_char(valid_from AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') || '|' ||
                               to_char(valid_until AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                        FROM access_link WHERE label = ?
                        """)
                .param(label).query(String.class).single();
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

    private static RequestPostProcessor owner(String email) {
        return user(email).roles("OWNER");
    }
}
