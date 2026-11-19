package welcomehome.browser;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.crypto.CryptoService;
import welcomehome.crypto.LinkTokenSigner;

/** open.js: what keeps a touch by mistake from opening a door, and what a guest sees after sliding. */
class GuestSlideTests extends BrowserTest {

    static final String OPENED = "Puerta abierta";

    @Autowired
    WireMockServer nuki;

    @Autowired
    LinkTokenSigner signer;

    @Autowired
    CryptoService crypto;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;
    UUID linkId;
    Locator slider;

    @BeforeEach
    void openAGuestPage() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        data.deleteOwners();
        var owner = data.owner("alice@example.com");
        data.apiToken(owner, crypto.encryptAccessToken(owner, "alices-token"));
        linkId = data.link(data.lock(owner, 17, "Casa"), "Ana");
        nukiOpens();
        page.navigate(url("/o/" + signer.sign(linkId, 1)));
        slider = page.locator("input[type=range]");
    }

    @Test
    void aSlideToTheEndOpensTheDoorOnce() {
        slideTo(1.0);

        assertThat(page.locator(".open-result")).containsText(OPENED);
        assertThat(slider).isDisabled();
        assertUnlatches(1);
    }

    /** An owner trying their link: the slide says what it would do, and no request leaves the page. */
    @Test
    void inAPreviewASlideToTheEndSaysWhatItWouldDoAndSendsNothing() {
        var requests = new java.util.ArrayList<String>();
        page.onRequest(request -> requests.add(request.method() + " " + request.url()));
        page.navigate(url("/o/" + signer.sign(linkId, 1) + "?preview=1"));
        slider = page.locator("input[type=range]");

        slideTo(1.0);

        assertThat(page.locator(".open-result")).hasText("Con el enlace de verdad, la puerta se abriría ahora.");
        assertThat(slider).isDisabled();
        page.waitForCondition(() -> slider.isEnabled());
        assertThat(slider).hasValue("0");
        org.assertj.core.api.Assertions.assertThat(requests).noneMatch(request -> request.startsWith("POST"));
        assertUnlatches(0);
        org.assertj.core.api.Assertions.assertThat(migrator.sql("SELECT count(*) FROM access_event")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void aSlideThatStopsShortGoesBackAndOpensNothing() {
        slideTo(0.7);

        assertThat(slider).hasValue("0");
        assertThat(slider).isEnabled();
        assertThat(page.locator(".open-result")).isHidden();
        assertUnlatches(0);
    }

    @Test
    void aTouchOnTheTrackAwayFromTheThumbDoesNotMoveItNorOpens() {
        var track = slider.boundingBox();
        var y = track.y + track.height / 2;
        var nearTheEnd = track.x + track.width * 0.97;

        touchAlong(nearTheEnd, nearTheEnd, y, false);
        assertThat(slider).hasValue("0");

        liftTheFinger();
        assertThat(slider).hasValue("0");
        assertThat(page.locator(".open-result")).isHidden();
        assertUnlatches(0);
    }

    @Test
    void fourPressesOfAKeyOpenAndThreeDoNot() {
        slider.focus();
        for (var press = 0; press < 3; press++) {
            page.keyboard().press("ArrowRight");
        }
        assertThat(slider).hasValue("75");
        assertUnlatches(0);

        page.keyboard().press("ArrowRight");

        assertThat(page.locator(".open-result")).containsText(OPENED);
        assertUnlatches(1);
    }

    @Test
    void afterOpeningTheThumbGoesBackButTheSliderStaysOff() {
        slideTo(1.0);
        assertThat(page.locator(".open-result")).containsText(OPENED);

        assertThat(slider).hasValue("0", new LocatorAssertions.HasValueOptions().setTimeout(5_000));

        assertThat(slider).isDisabled();
        // So that a slide that does nothing is not taken for a broken page.
        assertThat(page.locator(".slide-label"))
                .hasText(java.util.regex.Pattern.compile("Podrás deslizar en \\d+ s"));
        assertUnlatches(1);
    }

    /** The slider is the script's to show: one that moved and opened nothing would be worse than none. */
    @Test
    void theSliderShowsOnceItsScriptRunsAndTheNoticeOfAPageNotLoadedGoes() {
        assertThat(page.locator(".slide-to-open")).isVisible();
        assertThat(page.locator(".needs-script")).isHidden();
    }

    /** Nothing left the phone, so nothing is said of a door that may have opened. */
    @Test
    void withoutAConnectionNothingIsSentAndTheGuestIsToldSo() {
        context.setOffline(true);

        slideTo(1.0);

        assertThat(page.locator(".open-result")).hasText("No hay conexión a internet. Comprueba la conexión y vuelve a deslizar.");
        assertThat(slider).isEnabled();
        assertUnlatches(0);

        context.setOffline(false);
        slideTo(1.0);
        assertThat(page.locator(".open-result")).containsText(OPENED);
    }

    /** The request left and no answer came, as on a connection that dropped: the door may have opened. */
    @Test
    void aSlideThatGetsNoAnswerIsToldAsAnOpeningThatWasNotConfirmed() {
        page.route("**/api/open", route -> route.abort());

        slideTo(1.0);

        assertThat(page.locator(".open-result")).containsText("no lo ha confirmado");
        assertThat(slider).isEnabled();
    }

    /** Trying again at once only adds to them. */
    @Test
    void afterTooManyAttemptsTheSliderStaysOffAndCountsDown() {
        page.route("**/api/open", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                .setStatus(429).setContentType("application/json").setBody("{\"result\": \"too-many-attempts\"}")));

        slideTo(1.0);

        assertThat(page.locator(".open-result")).hasText("Demasiados intentos. Espera unos minutos y vuelve a probar.");
        assertThat(slider).isDisabled();
        assertThat(page.locator(".slide-label")).hasText(java.util.regex.Pattern.compile("Podrás deslizar en \\d+ s"),
                new LocatorAssertions.HasTextOptions().setTimeout(5_000));
    }

    @Test
    void afterAFailureItCanBeTriedAgainAtOnce() {
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(429)));

        slideTo(1.0);

        assertThat(page.locator(".open-result")).containsText("no responde");
        assertThat(slider).isEnabled();
        assertThat(slider).hasValue("0");

        nukiOpens();
        slideTo(1.0);
        assertThat(page.locator(".open-result")).containsText(OPENED);
    }

    @Test
    void aLinkCancelledWithThePageOpenSaysSoAndNeverWorksAgain() {
        data.revoke(linkId);

        slideTo(1.0);

        assertThat(page.locator(".open-result")).containsText("anulado");
        // Longer than a thumb stays at the end after an opening: here it stays, as the slider will not work again.
        page.waitForTimeout(2_500);
        assertThat(slider).isDisabled();
        assertThat(slider).hasValue("100");
        assertUnlatches(0);
    }

    private void nukiOpens() {
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(204)));
        nuki.stubFor(get(urlPathEqualTo("/smartlock/17/log")).willReturn(okJson("""
                [{"id": "1", "smartlockId": 17, "date": "%s", "action": 3, "trigger": 0, "state": 0}]
                """.formatted(Instant.now().plusSeconds(60)))));
    }

    /** A finger on the thumb, at the start of the track, up to this share of it. */
    private void slideTo(double share) {
        var track = slider.boundingBox();
        var y = track.y + track.height / 2;
        touchAlong(track.x + 12, track.x + track.width * share + (share >= 1 ? 10 : 0), y, true);
    }

    private void assertUnlatches(int times) {
        assertThat(nuki.findAll(postRequestedFor(urlPathEqualTo("/smartlock/17/action")))).hasSize(times);
    }
}
