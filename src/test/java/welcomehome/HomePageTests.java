package welcomehome;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
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
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as the locks tests, so they share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class HomePageTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;
    UUID casa;
    UUID garaje;
    UUID ana;
    UUID carlos;
    UUID bobsLink;

    @BeforeEach
    void insertLocksAndLinks() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        data.deleteOwners();
        var alice = data.owner(ALICE);
        casa = data.lock(alice, 17, "Casa", "Europe/Madrid");
        garaje = data.lock(alice, 18, "Garaje", "America/New_York");
        ana = insertLink(casa, "Ana");
        carlos = insertLink(garaje, "Carlos");
        bobsLink = insertLink(data.lock(data.owner(BOB), 19, "Casa de Bob", "Europe/Madrid"), "Invitado de Bob");
    }

    @Test
    void listsTheOwnersLocksWithTheirLinksAndHistory() {
        data.deactivate(garaje);

        assertThat(home()).containsSubsequence("Casa", "Garaje", "Ya no está en tu cuenta de Nuki")
                .as("a lock's name leads to it").contains("<a href=\"/locks/" + casa + "\">Casa</a>")
                .contains("href=\"/locks/" + casa + "/links\"", "href=\"/locks/" + casa + "/history\"",
                        "href=\"/locks/" + garaje + "/links\"")
                .doesNotContain("Casa de Bob");
    }

    /** By their dates: a link made for a guest long gone still opens the door, and its owner may not remember it. */
    @Test
    void eachLockSaysHowManyOfItsLinksCanOpenNow() {
        assertThat(home()).as("Ana's and Carlos's, one per lock").containsSubsequence(
                "Casa", ">1 enlace puede abrir ahora</a>", "Garaje", ">1 enlace puede abrir ahora</a>");

        data.link(casa, "Bea", "now() - interval '90 days'", "now() + interval '90 days'");
        data.link(casa, "Dani", "now() + interval '1 day'", "now() + interval '2 days'");
        data.link(casa, "Eva", "now() - interval '2 days'", "now() - interval '1 day'");
        data.link(casa, "Fer", "now() - interval '1 day'", "now() + interval '1 day'", "now()");
        data.deleteLink(carlos);

        assertThat(home()).as("not the pending, the expired or the revoked one, nor Bob's")
                .containsSubsequence("Casa", ">2 enlaces pueden abrir ahora</a>", "Garaje")
                .doesNotContain("1 enlace puede abrir ahora");
    }

    @Test
    void eachActiveLockOffersToCreateALinkAtOnce() {
        data.deactivate(garaje);

        assertThat(home()).contains("href=\"/locks/" + casa + "/links#new\"", "Crear enlace")
                .doesNotContain("href=\"/locks/" + garaje + "/links#new\"");
    }

    @Test
    void ownerWithoutLocksIsToldWhereToImportThem() {
        data.deleteLocks();

        assertThat(home()).contains("Todavía no tienes cerraduras", "href=\"/locks\"");
    }

    @Test
    void showsTheLatestAttemptsOfEveryLockNewestFirstInEachLocksTimeZone() {
        insertEvent(ana, "2030-07-01T10:00:00Z", "DENIED", "expired");
        insertEvent(carlos, "2030-07-02T10:00:00Z", "OK", "opened");
        insertEvent(ana, "2030-07-03T10:00:00Z", "ERROR", "night-mode");
        insertEvent(bobsLink, "2030-07-04T10:00:00Z", "OK", "opened");

        assertThat(home()).containsSubsequence(
                        "03/07/2030 12:00:00", "Casa", "Ana", "Error: modo nocturno",
                        "02/07/2030 06:00:00", "Garaje", "Carlos", "Abierta",
                        "01/07/2030 12:00:00", "Casa", "Ana", "Denegado: caducado")
                .contains("href=\"/locks/" + casa + "/history?link=" + ana + "\"")
                .as("another owner's attempts").doesNotContain("Invitado de Bob", "04/07/2030");
    }

    @Test
    void showsOnlyTheLastTenAttempts() {
        for (var day = 1; day <= 11; day++) {
            insertEvent(ana, "2030-07-%02dT10:00:00Z".formatted(day), "OK", "opened");
        }

        assertThat(home()).contains(">11/07/2030 12:00:00<", ">02/07/2030 12:00:00<").doesNotContain(">01/07/2030 12:00:00<");
    }

    @Test
    void attemptsShowAsInTheHistoryWithTheLockAndTheOrigin() {
        insertEvent(ana, "2030-07-01T10:00:00Z", "OK", "opened");
        data.country("ES");

        var home = home();
        var history = body(mvc.get().uri("/locks/" + casa + "/history").with(SignedIn.owner(ALICE)).exchange());
        assertThat(home).containsSubsequence("Fecha", "Cerradura", "Enlace", "Resultado", "Origen")
                .contains("data-tooltip=\"España · 198.51.100.1\">🇪🇸</span>");
        assertThat(history).containsSubsequence("Fecha", "Enlace", "Resultado", "Origen")
                .contains("data-tooltip=\"España · 198.51.100.1\">🇪🇸</span>");
        assertThat(rowOf(home)).isEqualTo(rowOf(history).replace("<td class=\"main\">01/07/2030 12:00:00</td>",
                "<td class=\"main\">01/07/2030 12:00:00</td><td><a href=\"/locks/" + casa + "/history\">Casa</a></td>"));
    }

    @Test
    void saysWhenThereAreNoAttempts() {
        assertThat(home()).contains("Todavía no hay intentos de abrir");
    }

    @Test
    void menuMarksTheCurrentSectionAndConfigurationLeadsToItsSections() {
        assertThat(home()).contains("href=\"/locks\"", "href=\"/settings\"").doesNotContain("href=\"/settings/passkeys\"");
        assertThat(mvc.get().uri("/settings").with(SignedIn.owner(ALICE))).hasRedirectedUrl("/settings/nuki");
        assertThat(body(mvc.get().uri("/locks").with(SignedIn.owner(ALICE)).exchange()))
                .contains("<a href=\"/locks\" aria-current=\"page\">Cerraduras</a>");
        for (var section : new String[] {"/settings/nuki", "/settings/preferences", "/settings/notifications",
                "/settings/passkeys", "/settings/activity"}) {
            assertThat(body(mvc.get().uri(section).with(SignedIn.owner(ALICE)).exchange()))
                    .as(section)
                    .contains("<a href=\"/settings\" aria-current=\"page\">Configuración</a>",
                            "<a href=\"" + section + "\" aria-current=\"page\">",
                            "href=\"/settings/nuki\"", "href=\"/settings/preferences\"", "href=\"/settings/notifications\"",
                            "href=\"/settings/passkeys\"");
        }
    }

    @Test
    void configurationsFormerAddressesLeadToItsSections() {
        var moved = Map.of("/nuki/connection", "/settings/nuki", "/notifications", "/settings/notifications",
                "/passkeys", "/settings/passkeys", "/settings/language", "/settings/preferences");
        moved.forEach((from, to) -> assertThat(mvc.get().uri(from).with(SignedIn.owner(ALICE))).as(from)
                .hasStatus(HttpStatus.MOVED_PERMANENTLY).hasRedirectedUrl(to));
    }

    /** The first row of the page's last table, the attempts', without the spaces between its tags. */
    private static String rowOf(String page) {
        return page.replaceAll("(?s).*<tbody>\\s*(<tr>.*?</tr>).*", "$1").replaceAll("\\s+", " ")
                .replaceAll("> <", "><");
    }

    private String home() {
        return body(mvc.get().uri("/").with(SignedIn.owner(ALICE)).exchange());
    }

    private static String body(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void insertEvent(UUID linkId, String occurredAt, String result, String reason) {
        data.attempt(linkId, "timestamptz '" + occurredAt + "'", result, reason, "198.51.100.1");
    }

    private UUID insertLink(UUID lockId, String label) {
        return data.link(lockId, label, "now()", TestData.IN_A_DAY);
    }
}
