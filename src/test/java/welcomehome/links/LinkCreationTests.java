package welcomehome.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.net.URI;
import java.time.Instant;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.SignedIn;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.TestTime;
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
    TestTime time;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    LinkTokenSigner signer;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;
    UUID madridLock;
    UUID newYorkLock;
    UUID bobsLock;

    /**
     * The dates these tests send are in 2030: the app's clock stays before them, whenever the tests run. Nothing here
     * depends on rows dated by the database being before or after it.
     */
    @BeforeEach
    void stopTheClockBeforeTheDatesSent() {
        time.setTo(Instant.parse("2026-11-19T12:00:00Z"));
    }

    @BeforeEach
    void insertLocks() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        data.deleteOwners();
        var alice = data.owner(ALICE);
        madridLock = data.lock(alice, 17, "Casa", "Europe/Madrid");
        newYorkLock = data.lock(alice, 18, "Loft", "America/New_York");
        bobsLock = data.lock(data.owner(BOB), 19, "Apartamento de Bob", "Europe/Madrid");
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

    /**
     * In Madrid 02:30 does not exist on 31 March 2030, when the clocks go from 02:00 to 03:00, and comes twice on 27
     * October, when they go back from 03:00 to 02:00: a start typed in the gap moves forward by it, and an end typed
     * in the hour that comes twice is the first of the two.
     */
    @Test
    void aTimeTheClocksSkipMovesForwardAndOneThatComesTwiceIsTheFirst() {
        create(madridLock, "Gap", "2030-03-31T02:30", "2030-03-31T12:00");
        create(madridLock, "Overlap", "2030-10-26T12:00", "2030-10-27T02:30");

        assertThat(validity("Gap")).as("03:30 summer time, and noon").isEqualTo("2030-03-31T01:30:00Z|2030-03-31T10:00:00Z");
        assertThat(validity("Overlap")).as("noon, and 02:30 summer time").isEqualTo("2030-10-26T10:00:00Z|2030-10-27T00:30:00Z");
    }

    @Test
    void pageShowsTheCreatedLinkToCopy() {
        var flash = create(madridLock, "Ana", "2030-11-01T15:00", "2030-11-03T11:00").getMvcResult().getFlashMap();
        var url = (URI) flash.get("createdUrl");

        var page = mvc.get().uri("/locks/" + madridLock + "/links").with(SignedIn.owner(ALICE)).flashAttrs(flash).exchange();

        assertThat(page).hasStatusOk()
                .bodyText().contains("Enlace «Ana» creado", "01/11/2030 15:00", "03/11/2030 11:00",
                        "value=\"" + url + "\"", "Copiar");
        assertThat(page).bodyText().as("copied and shared with its words there too, as from its row")
                .containsSubsequence("id=\"link-url\"", "data-copy-text=\"Para entrar en Casa del 01/11/2030 15:00",
                        "data-copy-url=\"" + url + "\"", "data-share-url=\"" + url + "\"", "<table");
    }

    /** Sent days ahead, a link that opens already lets its guest open from home: said where it is sent from. */
    @Test
    void aLinkThatOpensAlreadyIsSaidSoOnCreatingItAndTheFormSaysToPutTheDayOfArrival() {
        var later = create(madridLock, "Ana", "2030-11-01T15:00", "2030-11-03T11:00").getMvcResult().getFlashMap();
        assertThat(mvc.get().uri("/locks/" + madridLock + "/links").with(SignedIn.owner(ALICE)).flashAttrs(later))
                .bodyText().doesNotContain("abre la puerta desde ya")
                .contains("Si lo envías antes de que llegue tu invitado, pon aquí el día y la hora de llegada.");

        var now = create(madridLock, "Bea", "2020-11-01T15:00", "2030-11-03T11:00").getMvcResult().getFlashMap();
        assertThat(mvc.get().uri("/locks/" + madridLock + "/links").with(SignedIn.owner(ALICE)).flashAttrs(now))
                .bodyText().containsSubsequence("Enlace «Bea» creado", "Este enlace abre la puerta desde ya: quien lo "
                        + "reciba puede abrirla ahora mismo, aunque todavía no haya llegado.", "id=\"link-url\"");
    }

    /** For an owner who may forget it: said once, on saving, for a link that can open for over 30 days. */
    @Test
    void aLinkThatCanOpenForOverThirtyDaysIsSaidSoOnCreatingIt() {
        var oneMonth = create(madridLock, "Ana", "2030-11-01T15:00", "2030-12-01T15:00").getMvcResult().getFlashMap();
        assertThat(oneMonth.get("createdDays")).as("30 days, to the minute").isNull();
        assertThat(mvc.get().uri("/locks/" + madridLock + "/links").with(SignedIn.owner(ALICE)).flashAttrs(oneMonth))
                .bodyText().doesNotContain("Anúlalo cuando deje de hacer falta");

        var longer = create(madridLock, "Bea", "2030-11-01T15:00", "2031-01-30T15:00").getMvcResult().getFlashMap();
        assertThat(longer.get("createdDays")).isEqualTo("90");
        assertThat(mvc.get().uri("/locks/" + madridLock + "/links").with(SignedIn.owner(ALICE)).flashAttrs(longer))
                .bodyText().contains("Este enlace puede abrir la puerta durante 90 días. Anúlalo cuando deje de hacer falta.");
    }

    @Test
    void formIsPrefilledInTheLocksTimeZone() {
        assertThat(mvc.get().uri("/locks/" + newYorkLock + "/links").with(SignedIn.owner(ALICE)))
                .hasStatusOk()
                .bodyText().contains("Enlaces de Loft", "hora oriental", "Crear enlace");
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
        data.deactivate(madridLock);

        assertThat(create(madridLock, "Ana", "2030-11-01T15:00", "2030-11-03T11:00"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyText().contains("no se pueden crear enlaces");
        assertThat(migrator.sql("SELECT count(*) FROM access_link").query(Long.class).single()).isZero();
    }

    @Test
    void anotherOwnersLockIsNotFound() {
        assertThat(mvc.get().uri("/locks/" + bobsLock + "/links").with(SignedIn.owner(ALICE))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(create(bobsLock, "Ana", "2030-11-01T15:00", "2030-11-03T11:00")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(migrator.sql("SELECT count(*) FROM access_link").query(Long.class).single()).isZero();
    }

    @Test
    void creatingRequiresTheCsrfToken() {
        assertThat(mvc.post().uri("/locks/" + madridLock + "/links").with(SignedIn.owner(ALICE))
                .param("label", "Ana").param("validFrom", "2030-11-01T15:00").param("validUntil", "2030-11-03T11:00"))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    private MvcTestResult create(UUID lockId, String label, String validFrom, String validUntil) {
        return mvc.post().uri("/locks/" + lockId + "/links").with(SignedIn.owner(ALICE)).with(csrf())
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
}
