package welcomehome.access;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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

@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class GuestPageTests {

    static final String OPEN_BUTTON = "id=\"open\"";
    static final String NOT_YET_VALID = "Este enlace todavía no es válido.";
    static final String EXPIRED = "Este enlace ha caducado. Pide uno nuevo a quien te lo envió.";
    static final String REVOKED = "Este enlace se ha anulado. Pide uno nuevo a quien te lo envió.";
    static final String UNAVAILABLE = "Esta puerta no está disponible ahora mismo.";
    static final String OUTSIDE_SCHEDULE_NOTICE = "<p role=\"alert\">A esta hora este enlace no abre la puerta.";
    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    static final String PRIVACY_NOTICE = "Al deslizar, quien te dio este enlace podrá ver la hora, tu país y tu "
            + "dirección IP. La IP se borra a los 90 días; lo demás, a los 365. Las copias de seguridad los conservan hasta 30 "
            + "días más.";

    /** Stopped by the tests that reason about today, so that they and the app cannot read two sides of midnight. */
    @Autowired
    TestTime time;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    LinkTokenSigner signer;

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    welcomehome.crypto.CryptoService crypto;

    /** Every GET below must leave it untouched: the guest page never asks Nuki for anything. */
    @Autowired
    WireMockServer nuki;

    JdbcClient migrator;
    TestData data;
    UUID lockId;

    @BeforeEach
    void insertAConnectedLock() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        data.deleteOwners();
        var ownerId = data.owner("alice@example.com");
        lockId = data.lock(ownerId, 17, "Casa de Alice", "America/New_York");
        // A token that works, so that anything asked of the lock provider would reach Nuki, and be seen below.
        data.connection(ownerId, crypto.encryptAccessToken(ownerId, "alices-nuki-token"));
        nuki.resetAll();
    }

    @AfterEach
    void getNeverReachesNuki() {
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void activeLinkShowsTheLockItsValidityAndTheOpenButton() {
        var link = insertLink("'2020-07-01T19:00:00Z'", "'2040-07-03T15:00:00Z'", false);

        var result = guestPage(signer.sign(link, 1));

        assertThat(result).hasStatusOk()
                .headers().hasValue("X-Robots-Tag", "noindex, nofollow");
        assertThat(body(result))
                .contains("Casa de Alice", "del mié 1 jul 2020, 15:00 al mar 3 jul 2040, 11:00 (hora oriental)",
                        OPEN_BUTTON, "<meta name=\"robots\" content=\"noindex, nofollow\">")
                .doesNotContain("Etiqueta privada");
    }

    /** A browser that asks for a language the app lacks gets Spanish: the guest may choose one they read. */
    @Test
    void theGuestMayChooseAnotherLanguageWhateverTheirBrowserAsksFor() {
        var token = signer.sign(insertLink("now() - interval '1 day'", "now() + interval '1 day'", false), 1);

        assertThat(body(mvc.get().uri("/o/" + token).header("Accept-Language", "nl").exchange()))
                .as("Dutch, which the app lacks").contains("Desliza para abrir", "aria-current=\"true\">Español</strong>",
                        "href=\"?lang=en\">English</a>", "href=\"?lang=de\">Deutsch</a>");
        assertThat(body(mvc.get().uri("/o/" + token + "?lang=en").header("Accept-Language", "nl").exchange()))
                .contains("Slide to open", "aria-current=\"true\">English</strong>", "href=\"?lang=es\">Español</a>");
        assertThat(body(mvc.get().uri("/o/" + token + "?lang=xx").exchange())).as("no such language")
                .contains("Desliza para abrir");
        assertThat(body(mvc.get().uri("/o/" + token + "?preview=1").exchange())).as("a preview stays one")
                .contains("href=\"?lang=en&amp;preview=1\"");
        assertThat(mvc.get().uri("/o/nonsense?lang=en")).hasStatus(HttpStatus.NOT_FOUND).bodyText()
                .contains("href=\"?lang=es\">Español</a>");
    }

    /** An owner trying their link is signed in, with a language of their own: the one they ask for still wins. */
    @Test
    void anOwnerPreviewingTheirLinkReadsItInTheLanguageTheyChooseThere() {
        var token = signer.sign(insertLink("now() - interval '1 day'", "now() + interval '1 day'", false), 1);
        var alice = SignedIn.owner("alice@example.com");

        assertThat(body(mvc.get().uri("/o/" + token + "?preview=1").with(alice).exchange()))
                .as("their own, until they choose").contains("Desliza para abrir");
        assertThat(body(mvc.get().uri("/o/" + token + "?lang=fr&preview=1").with(alice).exchange()))
                .contains("<html lang=\"fr\"", "Glissez pour ouvrir", "aria-current=\"true\">Français</strong>");
    }

    /** A guest who opens the page early and waits with it open gets the slider when the link can open. */
    @Test
    void aPageThatCannotOpenYetLoadsItselfAgainWhenItCan() {
        var soon = signer.sign(insertLink("now() + interval '10 minutes'", "now() + interval '1 day'", false), 1);
        var later = signer.sign(insertLink("now() + interval '30 days'", "now() + interval '31 days'", false), 1);
        var active = signer.sign(insertLink("now() - interval '1 day'", "now() + interval '1 day'", false), 1);

        assertThat(body(guestPage(soon))).containsPattern("<meta http-equiv=\"refresh\" content=\"(5[6-9]\\d|60[01])\">");
        assertThat(body(guestPage(later))).as("at most six hours ahead, and then again")
                .contains("<meta http-equiv=\"refresh\" content=\"21600\">");
        assertThat(body(guestPage(active))).doesNotContain("http-equiv=\"refresh\"");
    }

    /** The slider is hidden until its script shows it, and the page says what to do if it never does. */
    @Test
    void theSliderWaitsForItsScriptAndThePageCarriesWhatItSaysByItself() {
        var token = signer.sign(insertLink("now() - interval '1 day'", "now() + interval '1 day'", false), 1);

        assertThat(body(guestPage(token))).contains("<div class=\"slide-to-open\" hidden>",
                "Para abrir hace falta que esta página termine de cargar: vuelve a cargarla.",
                "data-outcome=\"offline\">No hay conexión a internet.",
                "data-outcome=\"too-many-attempts-pin\">Demasiados intentos. Espera unos minutos y vuelve a probar; "
                        + "si no sabes el PIN, pídeselo a quien te envió el enlace.");
    }

    /** Sliding is not a way into a page: said before any slider, and only where there is one. */
    @Test
    void theGuestIsToldBeforeAnySliderThatSlidingOpensARealDoor() {
        var active = insertLink("now() - interval '1 day'", "now() + interval '1 day'", false);
        var expired = insertLink("now() - interval '2 days'", "now() - interval '1 day'", false);

        assertThat(body(guestPage(signer.sign(active, 1)))).containsSubsequence(
                "<strong>Deslizar abre la puerta de verdad, en ese mismo momento. Hazlo solo cuando estés delante de ella.</strong>",
                "type=\"range\"");
        assertThat(body(guestPage(signer.sign(expired, 1)))).doesNotContain("Deslizar abre");
        data.forgetNuki();
        assertThat(body(guestPage(signer.sign(active, 1)))).as("unavailable").doesNotContain("Deslizar abre");
    }

    /** For its owner to see what their guest will: marked as such, and nothing is recorded, so nothing is said of it. */
    @Test
    void theGuestPageAsAPreviewSaysSoAndTellsItsSliderToSendNothing() {
        var token = signer.sign(insertLink("now() - interval '1 day'", "now() + interval '1 day'", false), 1);

        assertThat(body(guestPage(token))).contains("data-preview=\"false\"").doesNotContain("Vista de prueba");

        var preview = body(mvc.get().uri("/o/" + token + "?preview=1").exchange());
        assertThat(preview).contains("Vista de prueba: esto es lo que verá tu invitado. Aquí deslizar no abre la "
                        + "puerta ni envía nada a la cerradura.", "data-preview=\"true\"",
                        "data-preview-text=\"Con el enlace de verdad, la puerta se abriría ahora.\"",
                        "Deslizar abre la puerta de verdad")
                .doesNotContain("Al deslizar, quien te dio este enlace");
    }

    /** Only where the guest can slide, as only sliding records anything. */
    @Test
    void theGuestIsToldWhatSlidingKeepsAndForHowLong() {
        var active = insertLink("now() - interval '1 day'", "now() + interval '1 day'", false);
        var expired = insertLink("now() - interval '2 days'", "now() - interval '1 day'", false);

        assertThat(body(guestPage(signer.sign(active, 1)))).contains(PRIVACY_NOTICE);
        assertThat(body(guestPage(signer.sign(expired, 1)))).doesNotContain("Al deslizar");
        data.forgetNuki();
        assertThat(body(guestPage(signer.sign(active, 1)))).as("unavailable").doesNotContain("Al deslizar");
    }

    /** Whether the slide sends the guest's position: never where the door is, which a leaked link must not tell. */
    @Test
    void aDoorThatChecksWhereItsGuestsAreSaysSoButNotWhereItIs() {
        var link = signer.sign(insertLink("now() - interval '1 day'", "now() + interval '1 day'", false), 1);
        assertThat(body(guestPage(link))).contains("data-location-check=\"false\"").doesNotContain("a qué distancia");

        data.locationCheck(lockId, 40.416775, -3.703790, "CONFIRM_WHEN_FAR", 150);

        assertThat(body(guestPage(link))).contains("data-location-check=\"true\"",
                        "También se comprueba a qué distancia estás de la puerta: se guarda esa distancia, nunca dónde estás.",
                        "data-wait-text=\"Podrás deslizar en {0} s\"", "Parece que estás a unos {0} de la puerta")
                .doesNotContain("40.41", "3.70");
    }

    @Test
    void aLinkWithAPinTellsThePageToAskForItAfterTheSlideButNeverSaysWhichPin() {
        var link = insertLink("now() - interval '1 day'", "now() + interval '1 day'", false);
        var token = signer.sign(link, 1);
        assertThat(body(guestPage(token))).contains("data-pin-required=\"false\"").doesNotContain("pin-form");

        var owner = migrator.sql("SELECT owner_id FROM access_link WHERE id = ?").param(link).query(UUID.class).single();
        new TestData(migrator).pin(link, crypto.encryptLinkPin(owner, link, "4821"));

        assertThat(body(guestPage(token))).contains("data-pin-required=\"true\"").contains("class=\"pin-form\"")
                .doesNotContain("4821");
    }

    @Test
    void pendingLinkSaysWhenItStarts() {
        var link = insertLink("'2040-11-01T19:00:00Z'", "'2040-11-03T16:00:00Z'", false);

        assertThat(body(guestPage(signer.sign(link, 1))))
                .contains(NOT_YET_VALID, "a partir del jue 1 nov 2040, 15:00")
                .doesNotContain(OPEN_BUTTON);
    }

    @Test
    void pendingLinkSaysWhenItsScheduleFirstLetsItOpen() {
        // 1 November 2040 is a Thursday.
        var link = insertLink("'2040-11-01T19:00:00Z'", "'2040-11-10T16:00:00Z'", false);
        insertSchedule(link, DayOfWeek.FRIDAY, "09:00", "17:00");

        assertThat(body(guestPage(signer.sign(link, 1))))
                .contains(NOT_YET_VALID, "a partir del vie 2 nov 2040, 09:00", "<li>Viernes: 09:00–17:00</li>")
                .doesNotContain(OPEN_BUTTON);
    }

    @Test
    void withinItsScheduleALinkOffersToOpenAndShowsTheSchedule() {
        var link = insertLink("now() - interval '1 day'", "now() + interval '30 days'", false);
        var today = time.freeze().atZone(NEW_YORK).getDayOfWeek();
        insertSchedule(link, today, "00:00", "00:00");

        assertThat(body(guestPage(signer.sign(link, 1))))
                .contains(OPEN_BUTTON, "Solo en este horario:", ": todo el día</li>")
                .doesNotContain(OUTSIDE_SCHEDULE_NOTICE);
    }

    @Test
    void outsideItsScheduleALinkOffersNothingAndSaysWhenItOpens() {
        insertOpenerAsEntrance();
        var link = insertLink("now() - interval '1 day'", "now() + interval '30 days'", false);
        var tomorrow = time.freeze().atZone(NEW_YORK).plusDays(1);
        insertSchedule(link, tomorrow.getDayOfWeek(), "00:00", "00:00");

        assertThat(body(guestPage(signer.sign(link, 1))))
                .contains(OUTSIDE_SCHEDULE_NOTICE + " Podrás abrir a partir del "
                        + DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.of("es")).format(tomorrow) + ", 00:00")
                .doesNotContain(OPEN_BUTTON, "id=\"open-entrance\"", "/js/open.js");
    }

    @Test
    void outsideItsScheduleWithNoHoursLeftALinkSaysItWillNotOpenAgain() {
        var link = insertLink("now() - interval '1 day'", "now() + interval '1 hour'", false);
        insertSchedule(link, time.freeze().atZone(NEW_YORK).plusDays(3).getDayOfWeek(), "00:00", "00:00");

        assertThat(body(guestPage(signer.sign(link, 1))))
                .contains("Este enlace ya no volverá a abrir la puerta antes de caducar.")
                .doesNotContain(OPEN_BUTTON);
    }

    @Test
    void aLinkWithoutAScheduleSaysNothingOfIt() {
        var link = insertLink("now() - interval '1 day'", "now() + interval '1 day'", false);

        assertThat(body(guestPage(signer.sign(link, 1)))).contains(OPEN_BUTTON).doesNotContain("Solo en este horario");
    }

    @Test
    void aFinishedLinkSaysNothingOfItsSchedule() {
        var expired = insertLink("now() - interval '2 days'", "now() - interval '1 day'", false);
        insertSchedule(expired, DayOfWeek.MONDAY, "09:00", "17:00");

        assertThat(body(guestPage(signer.sign(expired, 1)))).contains(EXPIRED)
                .doesNotContain("Solo en este horario", OUTSIDE_SCHEDULE_NOTICE);
    }

    @Test
    void aFinishedLinkNoLongerSaysItOpensTheDoor() {
        var expired = insertLink("now() - interval '2 days'", "now() - interval '1 day'", false);
        var revoked = insertLink("now() - interval '1 day'", "now() + interval '1 day'", true);

        assertThat(body(guestPage(signer.sign(expired, 1)))).contains(EXPIRED).doesNotContain("Este enlace abre la puerta");
        assertThat(body(guestPage(signer.sign(revoked, 1)))).contains(REVOKED).doesNotContain("Este enlace abre la puerta");
    }

    /** Not the words of a revoked link, which send the guest to ask for a new one: this one may open again. */
    @Test
    void aPausedLinkSaysSoAndOffersNoButton() {
        var paused = insertLink("now() - interval '1 day'", "now() + interval '1 day'", false);
        data.pause(paused);

        assertThat(body(guestPage(signer.sign(paused, 1))))
                .contains("Este enlace está en pausa: ahora no abre la puerta. Pregunta a quien te lo envió.")
                .doesNotContain(OPEN_BUTTON, REVOKED, "Este enlace abre la puerta");
    }

    @Test
    void expiredOrRevokedLinksOfferNoButton() {
        var expired = insertLink("now() - interval '2 days'", "now() - interval '1 day'", false);
        var revoked = insertLink("now() - interval '1 day'", "now() + interval '1 day'", true);

        assertThat(body(guestPage(signer.sign(expired, 1)))).contains(EXPIRED).doesNotContain(OPEN_BUTTON);
        assertThat(body(guestPage(signer.sign(revoked, 1)))).contains(REVOKED).doesNotContain(OPEN_BUTTON);
    }

    @Test
    void inactiveLockOrMissingNukiConnectionIsUnavailableBeforeTheGuestTries() {
        var token = signer.sign(insertLink("now() - interval '1 day'", "now() + interval '1 day'", false), 1);

        data.deactivateLocks();
        assertUnavailable(token);

        data.activateLocks();
        data.disconnectNuki();
        assertUnavailable(token);

        data.forgetNuki();
        assertUnavailable(token);
    }

    @Test
    void unavailabilityDoesNotHideTheLinksOwnState() {
        data.forgetNuki();
        var expired = insertLink("now() - interval '2 days'", "now() - interval '1 day'", false);

        assertThat(body(guestPage(signer.sign(expired, 1)))).contains(EXPIRED).doesNotContain(UNAVAILABLE);
    }

    @Test
    void slideToOpenShowsTheSameMessagesAsThePage() {
        var link = insertLink("now() - interval '1 day'", "now() + interval '1 day'", false);

        assertThat(body(guestPage(signer.sign(link, 1)))).as("what the slider shows, which the page carries")
                .contains(NOT_YET_VALID, EXPIRED, REVOKED, UNAVAILABLE)
                .as("a slide to open, not a button a touch by mistake would press")
                .containsPattern("<input type=\"range\" id=\"open\" min=\"0\" max=\"100\"[^>]*aria-label=\"Desliza para abrir\"");
        var script = mvc.get().uri("/js/open.js").exchange();
        assertThat(script).hasStatusOk();
        assertThat(body(script)).as("the page carries no CSRF token to send").doesNotContain("_csrf");
    }

    @Test
    void linkWithAnEntranceOffersTheEntranceFirstThenTheDoor() {
        insertOpenerAsEntrance();
        var link = insertLink("now() - interval '1 day'", "now() + interval '1 day'", false);

        assertThat(body(guestPage(signer.sign(link, 1))))
                .containsPattern("(?s)<h2>Portal</h2>.*<input type=\"range\" id=\"open-entrance\"[^>]*"
                        + "aria-label=\"Desliza para abrir el portal\"\\s+data-door=\"entrance\" data-opener=\"true\"\\s+data-location-check=\"false\"\\s+data-pin-required=\"false\"\\s+data-preview=\"false\">"
                        + ".*<h2>Puerta</h2>.*<input type=\"range\" id=\"open\"[^>]*"
                        + "aria-label=\"Desliza para abrir\"\\s+data-door=\"lock\" data-opener=\"false\"\\s+data-location-check=\"false\"\\s+data-pin-required=\"false\"\\s+data-preview=\"false\">")
                .as("each slider carries its own messages")
                .contains("Portal abierto: empújalo ya", "Puerta abierta: empújala.");
    }

    @Test
    void inactiveEntranceLeavesTheDoorToOpen() {
        var portal = insertOpenerAsEntrance();
        data.deactivate(portal);
        var link = insertLink("now() - interval '1 day'", "now() + interval '1 day'", false);

        assertThat(body(guestPage(signer.sign(link, 1))))
                .contains("<p role=\"alert\">El portal no está disponible ahora mismo", OPEN_BUTTON)
                .doesNotContain("id=\"open-entrance\"", "<p role=\"alert\">" + UNAVAILABLE);
    }

    @Test
    void anOpenersOwnLinkSpeaksOfTheEntrance() {
        lockId = insertOpenerAsEntrance();
        var link = insertLink("now() - interval '1 day'", "now() + interval '1 day'", false);

        assertThat(body(guestPage(signer.sign(link, 1))))
                .containsPattern("<input type=\"range\" id=\"open\"[^>]*aria-label=\"Desliza para abrir el portal\"")
                .contains("Portal abierto: empújalo ya")
                .doesNotContain("<h2>");
    }

    @Test
    void whileALinkCanOpenThePageShowsTheLocksInstructionsAsTheOwnerWroteThem() {
        data.guestInstructions(lockId, "Calle Mayor 1, 3.º B.\nLa llave, <b>en el buzón</b>.");
        var instructions = "<p class=\"guest-instructions\">Calle Mayor 1, 3.º B.\n"
                + "La llave, &lt;b&gt;en el buzón&lt;/b&gt;.</p>";
        var active = insertLink(TestData.AN_HOUR_AGO, TestData.IN_A_DAY, false);
        var pending = insertLink("'2040-11-01T19:00:00Z'", "'2040-11-03T16:00:00Z'", false);

        assertThat(body(guestPage(signer.sign(active, 1)))).as("active").contains(instructions);
        assertThat(body(guestPage(signer.sign(pending, 1)))).as("pending").contains(instructions);
    }

    /** They may say where the door is, or where a key is: not for whoever still has a link that ended. */
    @Test
    void aLinkThatEndedShowsNoInstructions() {
        data.guestInstructions(lockId, "Calle Mayor 1, 3.º B.");
        var expired = insertLink("'2020-07-01T19:00:00Z'", "'2020-07-03T15:00:00Z'", false);
        var revoked = insertLink(TestData.AN_HOUR_AGO, TestData.IN_A_DAY, true);

        assertThat(body(guestPage(signer.sign(expired, 1)))).as("expired")
                .contains(EXPIRED).doesNotContain("Calle Mayor", "guest-instructions");
        assertThat(body(guestPage(signer.sign(revoked, 1)))).as("revoked")
                .contains(REVOKED).doesNotContain("Calle Mayor", "guest-instructions");
    }

    @Test
    void withoutInstructionsThePageShowsNone() {
        assertThat(body(guestPage(signer.sign(insertLink(TestData.AN_HOUR_AGO, TestData.IN_A_DAY, false), 1))))
                .contains(OPEN_BUTTON).doesNotContain("guest-instructions");
    }

    @Test
    void everyInvalidTokenGetsTheSameNotFoundPage() {
        var link = insertLink("now() - interval '1 day'", "now() + interval '1 day'", false);
        var token = signer.sign(link, 1);
        migrator.sql("UPDATE access_link SET token_version = 2").update();

        for (var invalid : new String[] {
                token,
                signer.sign(link, 2).substring(0, 40) + "AAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                signer.sign(UUID.randomUUID(), 1),
                "not-a-token",
                "x".repeat(200)}) {
            var result = guestPage(invalid);
            assertThat(result).as(invalid).hasStatus(HttpStatus.NOT_FOUND)
                    .headers().hasValue("X-Robots-Tag", "noindex, nofollow");
            assertThat(body(result)).as(invalid).contains("Enlace no válido").doesNotContain("Casa de Alice");
        }
        assertThat(guestPage(signer.sign(link, 2))).as("the current token").hasStatusOk();
    }

    @Test
    void guestPageNeedsNoSignIn() {
        var link = insertLink("now() - interval '1 day'", "now() + interval '1 day'", false);

        assertThat(guestPage(signer.sign(link, 1))).hasStatusOk();
    }

    @Test
    void guestPagesCreateNoSessionAndCarryNoCsrfToken() {
        var link = insertLink("now() - interval '1 day'", "now() + interval '1 day'", false);

        for (var token : new String[] {signer.sign(link, 1), "nonsense"}) {
            var result = guestPage(token);
            assertThat(body(result)).as(token).doesNotContain("_csrf");
            assertThat(result.getRequest().getSession(false)).as(token).isNull();
        }
        assertThat(mvc.get().uri("/login")).as("the pages that post forms keep theirs")
                .bodyText().contains("name=\"_csrf\"");
    }

    /** An Opener "Portal" at the entrance of the lock's building. */
    private UUID insertOpenerAsEntrance() {
        return data.entrance(lockId, 19, "Portal");
    }

    private void assertUnavailable(String token) {
        assertThat(body(guestPage(token))).contains(UNAVAILABLE).doesNotContain(OPEN_BUTTON);
    }

    private MvcTestResult guestPage(String token) {
        return mvc.get().uri("/o/" + token).exchange();
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void insertSchedule(UUID link, DayOfWeek day, String opens, String closes) {
        data.schedule(link, day, opens, closes);
    }

    private UUID insertLink(String validFrom, String validUntil, boolean revoked) {
        return data.link(lockId, "Etiqueta privada", validFrom, validUntil, revoked ? "now()" : null);
    }
}
