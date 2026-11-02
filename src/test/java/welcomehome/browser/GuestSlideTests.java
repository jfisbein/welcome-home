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
        migrator.sql("DELETE FROM owner").update();
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
        assertUnlatches(1);
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
        migrator.sql("UPDATE access_link SET revoked_at = now() WHERE id = ?").param(linkId).update();

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
