package welcomehome.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

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
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as the locks tests, so they share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class AccessHistoryTests {

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
    UUID bea;
    UUID carlos;
    UUID bobsLock;
    UUID bobsLink;

    @BeforeEach
    void insertLocksAndLinks() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        data.deleteOwners();
        var alice = data.owner(ALICE);
        casa = data.lock(alice, 17, "Casa", "Europe/Madrid");
        garaje = data.lock(alice, 18, "Garaje", "Europe/Madrid");
        ana = insertLink(casa, "Ana");
        bea = insertLink(casa, "Bea");
        carlos = insertLink(garaje, "Carlos");
        bobsLock = data.lock(data.owner(BOB), 19, "Casa de Bob", "Europe/Madrid");
        bobsLink = insertLink(bobsLock, "Invitado de Bob");
    }

    @Test
    void lockHistoryShowsItsAttemptsNewestFirstInTheLocksTimeZone() {
        insertEvent(ana, "2030-07-01T10:00:00Z", "DENIED", "not-yet-valid", "198.51.100.1");
        insertEvent(bea, "2030-07-02T10:00:00Z", "OK", "opened", "198.51.100.2");
        insertEvent(ana, "2030-07-03T10:00:00Z", "ERROR", "night-mode", "2001:db8::3");
        insertEvent(carlos, "2030-07-04T10:00:00Z", "OK", "opened", "198.51.100.4");

        assertThat(body(history(casa, ""))).containsSubsequence(
                        "03/07/2030 12:00:00", "Ana", "Error: modo nocturno", "2001:db8::3",
                        "02/07/2030 12:00:00", "Bea", "Abierta", "198.51.100.2",
                        "01/07/2030 12:00:00", "Ana", "Denegado: todavía no válido", "198.51.100.1")
                .as("another lock's attempts").doesNotContain("Carlos", "198.51.100.4")
                .doesNotContain("Anteriores", "Más recientes");
    }

    /** The guest may not have been there: until now only the email said so. */
    @Test
    void aDoorOpenedOnlyBecauseItsGuestConfirmedBeingThereSaysSo() {
        data.attemptFrom(ana, "timestamptz '2030-07-01T10:00:00Z'", "OK", "opened", 3200, true);
        data.attemptFrom(bea, "timestamptz '2030-07-02T10:00:00Z'", "OK", "opened", 40, false);
        data.attemptFrom(ana, "timestamptz '2030-07-03T10:00:00Z'", "OK", "opened", null, true);

        assertThat(body(history(casa, ""))).containsSubsequence(
                "Ana", "<strong>Abierta tras confirmar que estaba delante</strong>",
                "Bea", "<strong>Abierta</strong>", "a 40 m de la puerta",
                "Ana", "<strong>Abierta tras confirmar que estaba delante</strong>", "a 3,2 km de la puerta");
    }

    @Test
    void linkHistoryShowsOnlyThatLinksAttempts() {
        insertEvent(ana, "2030-07-01T10:00:00Z", "OK", "opened", "198.51.100.1");
        insertEvent(bea, "2030-07-02T10:00:00Z", "OK", "opened", "198.51.100.2");

        assertThat(body(history(casa, "?link=" + ana))).contains("Solo el enlace «Ana»", "198.51.100.1")
                .doesNotContain("198.51.100.2");
    }

    /**
     * An attempt at Casa's building entrance is one of Ana's link's, so it shows with Casa's, named after the Opener;
     * the Opener's own history shows it too, leading to Ana's link under Casa.
     */
    @Test
    void attemptsAtTheBuildingEntranceShowWithTheLinkAndTheOpener() {
        var portal = data.entrance(casa, 20, "Portal del 5");
        insertEvent(ana, "2030-07-01T10:00:00Z", "OK", "opened", "198.51.100.1");
        insertEvent(ana, "2030-07-01T09:58:00Z", "OK", "opened", "198.51.100.2");
        data.atEntrance("198.51.100.2", portal);

        assertThat(body(history(casa, "?link=" + ana))).containsSubsequence(
                "12:00:00", "Ana", "198.51.100.1", "11:58:00", "Ana", "Portal: Portal del 5", "198.51.100.2");
        assertThat(body(history(casa, ""))).contains("Portal: Portal del 5", "198.51.100.1");
        assertThat(body(history(portal, ""))).contains("Portal: Portal del 5", "198.51.100.2",
                        "href=\"/locks/" + casa + "/history?link=" + ana + "\"")
                .doesNotContain("198.51.100.1");
        assertThat(body(mvc.get().uri("/").with(SignedIn.owner(ALICE)).exchange())).as("the home page's latest attempts")
                .contains("Portal: Portal del 5");
    }

    @Test
    void historyComesInPagesOfFifty() {
        migrator.sql("""
                        INSERT INTO access_event (owner_id, link_id, lock_id, occurred_at, result, reason, ip)
                        SELECT owner_id, id, lock_id, timestamptz '2030-07-01T10:00:00Z' + n * interval '1 minute',
                               'OK', 'opened', ('10.0.0.' || n)::inet
                        FROM access_link, generate_series(1, 51) AS n WHERE id = ?
                        """)
                .param(ana).update();

        var first = body(history(casa, "?link=" + ana));
        assertThat(first).contains(">10.0.0.51<", ">10.0.0.2<").doesNotContain(">10.0.0.1<", "Más recientes")
                .contains("Anteriores");
        var older = first.replaceAll("(?s).*href=\"([^\"]*)\">Anteriores.*", "$1").replace("&amp;", "&");
        assertThat(older).startsWith("/locks/" + casa + "/history?link=" + ana + "&before=");

        var second = body(mvc.get().uri(older).with(SignedIn.owner(ALICE)).exchange());
        assertThat(second).contains(">10.0.0.1<", "Más recientes").doesNotContain(">10.0.0.2<", "Anteriores");
    }

    @Test
    void attemptsAtTheSameInstantAreNeitherLostNorRepeatedAcrossPages() {
        migrator.sql("""
                        INSERT INTO access_event (owner_id, link_id, lock_id, occurred_at, result, reason, ip)
                        SELECT owner_id, id, lock_id, timestamptz '2030-07-01T10:00:00Z', 'OK', 'opened',
                               ('10.0.1.' || n)::inet
                        FROM access_link, generate_series(1, 52) AS n WHERE id = ?
                        """)
                .param(ana).update();

        var first = body(history(casa, "?link=" + ana));
        var older = first.replaceAll("(?s).*href=\"([^\"]*)\">Anteriores.*", "$1").replace("&amp;", "&");
        var second = body(mvc.get().uri(older).with(SignedIn.owner(ALICE)).exchange());

        var shown = new ArrayList<String>();
        for (var page : List.of(first, second)) {
            var ips = Pattern.compile(">(10\\.0\\.1\\.\\d+)<").matcher(page);
            while (ips.find()) {
                shown.add(ips.group(1));
            }
        }
        assertThat(shown).hasSize(52).doesNotHaveDuplicates();

        insertEvent(ana, "2030-07-01T09:00:00Z", "OK", "opened", "10.0.2.1");
        assertThat(body(history(casa, "?link=" + ana + "&before=2030-07-01T10:00:00Z")))
                .as("a URL from an earlier version, without beforeId: only attempts strictly before it")
                .contains(">10.0.2.1<").doesNotContain(">10.0.1.");
    }

    @Test
    void theOriginIsTheCountrysFlagWithTheCountryAndIpInATooltip() {
        insertEvent(ana, "2030-07-01T10:00:00Z", "OK", "opened", "198.51.100.7");
        insertEvent(ana, "2030-07-01T11:00:00Z", "OK", "opened", "198.51.100.8");
        insertEvent(ana, "2030-07-01T12:00:00Z", "OK", "opened", "198.51.100.9");
        data.country("198.51.100.8", "ES");
        data.country("198.51.100.9", "T1");

        assertThat(body(history(casa, ""))).as("a flag named for those who cannot see it, Tor by name, else the IP")
                .containsPattern("<span role=\"img\" tabindex=\"0\" data-placement=\"left\"\\s+aria-label=\"España · 198\\.51\\.100\\.8\"\\s+"
                        + "data-tooltip=\"España · 198\\.51\\.100\\.8\">🇪🇸</span>")
                .containsPattern("<small tabindex=\"0\" data-placement=\"left\"\\s+data-tooltip=\"198\\.51\\.100\\.9\">Tor</small>")
                .contains("<small>198.51.100.7</small>")
                .doesNotContain(">198.51.100.8<", ">198.51.100.9<");
        data.language(ALICE, "en");
        assertThat(body(history(casa, ""))).contains("data-tooltip=\"Spain · 198.51.100.8\">🇪🇸<");
    }

    @Test
    void aFlagIsTheCodesLettersAsRegionalIndicators() {
        assertThat(AttemptRows.flag("ES")).isEqualTo("🇪🇸");
        assertThat(AttemptRows.flag("GB")).isEqualTo("🇬🇧");
        assertThat(AttemptRows.flag("T1")).isNull();
    }

    @Test
    void unknownReasonsShowAsRecorded() {
        insertEvent(ana, "2030-07-01T10:00:00Z", "DENIED", "some-new-reason", "198.51.100.1");

        assertThat(body(history(casa, ""))).contains("some-new-reason");
    }

    @Test
    void lockWithoutAttemptsSaysSo() {
        assertThat(body(history(casa, ""))).contains("Todavía no hay intentos de abrir");
    }

    @Test
    void anotherOwnersLockOrALinkOfAnotherLockIsNotFound() {
        assertThat(history(bobsLock, "")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(history(casa, "?link=" + bobsLink)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(history(casa, "?link=" + carlos)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(history(casa, "?link=" + UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void anotherOwnersAttemptsNeverShow() {
        insertEvent(bobsLink, "2030-07-01T10:00:00Z", "OK", "opened", "198.51.100.9");

        assertThat(body(history(casa, ""))).doesNotContain("198.51.100.9");
    }

    /** "Did anyone get in?" without reading every refusal. */
    @Test
    void theHistoryIsNarrowedToWhatItsAttemptsCameTo() {
        insertEvent(ana, "2030-07-01T10:00:00Z", "DENIED", "not-yet-valid", "198.51.100.1");
        insertEvent(bea, "2030-07-02T10:00:00Z", "OK", "opened", "198.51.100.2");
        insertEvent(ana, "2030-07-03T10:00:00Z", "ERROR", "night-mode", "198.51.100.3");

        assertThat(body(history(casa, "?result=OK"))).contains("198.51.100.2")
                .doesNotContain("198.51.100.1", "198.51.100.3")
                .as("the form says what is shown").contains("<option value=\"OK\" selected=\"selected\">Aperturas");
        assertThat(body(history(casa, "?result=ERROR"))).contains("198.51.100.3")
                .doesNotContain("198.51.100.1", "198.51.100.2");
        assertThat(body(history(casa, "?result=DENIED"))).contains("198.51.100.1")
                .doesNotContain("198.51.100.2", "198.51.100.3");
        assertThat(body(history(casa, "?result="))).as("all of them")
                .contains("198.51.100.1", "198.51.100.2", "198.51.100.3").doesNotContain("selected=");
        assertThat(body(history(casa, "?result=OK&link=" + ana))).as("a link's, narrowed, keeps its link in the form")
                .contains("Ningún intento con ese filtro.", "name=\"link\" value=\"" + ana + "\"")
                .doesNotContain("198.51.100.", "Todavía no hay intentos de abrir");
    }

    /** "Who opened on Saturday night?": the day asked for is shown whole, as its lock's clock had it, and then back. */
    @Test
    void theHistoryStartsAtADayOfTheLocksCalendar() {
        insertEvent(ana, "2030-07-01T10:00:00Z", "OK", "opened", "198.51.100.1");
        insertEvent(ana, "2030-07-02T21:59:59Z", "OK", "opened", "198.51.100.2");
        insertEvent(ana, "2030-07-02T22:00:00Z", "OK", "opened", "198.51.100.3");

        var page = body(history(casa, "?until=2030-07-02"));

        assertThat(page).as("23:59:59 in Madrid is that day; midnight is the next").containsSubsequence(
                        "02/07/2030 23:59:59", "198.51.100.2", "01/07/2030 12:00:00", "198.51.100.1")
                .doesNotContain("198.51.100.3")
                .as("the form keeps the day, and the latest are one press away")
                .contains("name=\"until\" value=\"2030-07-02\"", "Más recientes");
        assertThat(body(history(casa, "?until=2030-06-30"))).contains("Ningún intento con ese filtro.");
    }

    @Test
    void aNarrowedHistoryStaysNarrowedOnItsNextPage() {
        migrator.sql("""
                        INSERT INTO access_event (owner_id, link_id, lock_id, occurred_at, result, reason, ip)
                        SELECT owner_id, id, lock_id, timestamptz '2030-07-01T10:00:00Z' + n * interval '1 minute',
                               CASE WHEN n % 2 = 0 THEN 'OK' ELSE 'DENIED' END,
                               CASE WHEN n % 2 = 0 THEN 'opened' ELSE 'expired' END, ('10.0.0.' || n)::inet
                        FROM access_link, generate_series(1, 104) AS n WHERE id = ?
                        """)
                .param(ana).update();

        var first = body(history(casa, "?result=OK&until=2030-07-01"));
        assertThat(first).contains(">10.0.0.104<", ">10.0.0.6<").doesNotContain(">10.0.0.4<", ">10.0.0.103<");
        var older = first.replaceAll("(?s).*href=\"([^\"]*)\">Anteriores.*", "$1").replace("&amp;", "&");
        assertThat(older).startsWith("/locks/" + casa + "/history?result=OK&before=");

        var second = body(mvc.get().uri(older).with(SignedIn.owner(ALICE)).exchange());
        assertThat(second).contains(">10.0.0.4<", ">10.0.0.2<").doesNotContain(">10.0.0.6<", ">10.0.0.3<", "Anteriores");
    }

    @Test
    void malformedParametersAreBadRequests() {
        assertThat(history(casa, "?result=MAYBE")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(history(casa, "?until=saturday")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(history(casa, "?before=yesterday")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(history(casa, "?before=2030-07-01T10:00:00Z&beforeId=nonsense")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(history(casa, "?link=ana")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void lockAndLinkPagesLeadToTheHistory() {
        var lockHistory = "href=\"/locks/" + casa + "/history\"";
        assertThat(body(mvc.get().uri("/locks").with(SignedIn.owner(ALICE)).exchange())).as("the list of locks")
                .contains(lockHistory);
        assertThat(body(mvc.get().uri("/locks/" + casa).with(SignedIn.owner(ALICE)).exchange())).as("the lock")
                .contains(lockHistory);
        assertThat(body(mvc.get().uri("/locks/" + casa + "/links").with(SignedIn.owner(ALICE)).exchange())).as("its links")
                .contains(lockHistory, "href=\"/locks/" + casa + "/history?link=" + ana + "\"");
    }

    private MvcTestResult history(UUID lockId, String query) {
        return mvc.get().uri("/locks/" + lockId + "/history" + query).with(SignedIn.owner(ALICE)).exchange();
    }

    private static String body(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void insertEvent(UUID linkId, String occurredAt, String result, String reason, String ip) {
        data.attempt(linkId, "timestamptz '" + occurredAt + "'", result, reason, ip);
    }

    private UUID insertLink(UUID lockId, String label) {
        return data.link(lockId, label, "now()", TestData.IN_A_DAY);
    }
}
