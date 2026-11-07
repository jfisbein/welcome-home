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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.crypto.CryptoService;
import welcomehome.crypto.LinkTokenSigner;

/** open.js for a link with a PIN: it is asked for after the slide, and nothing reaches the lock without it. */
class GuestPinTests extends BrowserTest {

    @Autowired
    WireMockServer nuki;

    @Autowired
    LinkTokenSigner signer;

    @Autowired
    CryptoService crypto;

    @Autowired
    PostgreSQLContainer postgres;

    Locator slider;
    Locator pinForm;
    Locator pinField;

    @BeforeEach
    void openAGuestPageWithAPin() {
        nuki.resetAll();
        var migrator = TestDatabaseConfiguration.asMigrator(postgres);
        var data = new TestData(migrator);
        migrator.sql("DELETE FROM owner").update();
        var owner = data.owner("alice@example.com");
        data.apiToken(owner, crypto.encryptAccessToken(owner, "alices-token"));
        var link = data.link(data.lock(owner, 17, "Casa"), "Ana");
        data.pin(link, crypto.encryptLinkPin(owner, link, "4821"));
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(204)));
        nuki.stubFor(get(urlPathEqualTo("/smartlock/17/log")).willReturn(okJson("""
                [{"id": "1", "smartlockId": 17, "date": "%s", "action": 3, "trigger": 0, "state": 0}]
                """.formatted(Instant.now().plusSeconds(60)))));
        page.navigate(url("/o/" + signer.sign(link, 1)));
        slider = page.locator("input[type=range]");
        pinForm = page.locator(".pin-form");
        pinField = pinForm.locator("input");
    }

    @Test
    void theSlideAsksForThePinAndOpensNothingYet() {
        assertThat(pinForm).isHidden();

        slideToTheEnd();

        assertThat(pinForm).isVisible();
        assertThat(pinField).isFocused();
        assertThat(slider).isDisabled();
        assertUnlatches(0);
    }

    @Test
    void aWrongPinSaysSoAndLetsTheGuestTypeAgainWithoutSlidingAgain() {
        slideToTheEnd();
        pinField.fill("1234");
        pinForm.locator("button[type=submit]").click();

        assertThat(page.locator(".open-result")).containsText("El PIN no es correcto");
        assertThat(pinField).hasValue("");
        assertThat(pinField).hasAttribute("aria-invalid", "true");
        assertThat(pinField).isFocused();
        assertUnlatches(0);

        pinField.fill("4821");
        pinField.press("Enter");

        assertThat(page.locator(".open-result")).containsText("Puerta abierta");
        assertThat(pinForm).isHidden();
        assertUnlatches(1);
    }

    @Test
    void cancellingGivesTheSliderBack() {
        slideToTheEnd();

        pinForm.locator("[data-cancel-pin]").click();

        assertThat(pinForm).isHidden();
        assertThat(slider).isEnabled();
        assertThat(slider).hasValue("0");
        assertUnlatches(0);
    }

    @Test
    void theFormAfterTheSlideHasNoAccessibilityViolationsInLightAndDark() {
        slideToTheEnd();
        assertThat(pinForm).isVisible();
        for (var scheme : com.microsoft.playwright.options.ColorScheme.values()) {
            if (scheme == com.microsoft.playwright.options.ColorScheme.NO_PREFERENCE) {
                continue;
            }
            page.emulateMedia(new com.microsoft.playwright.Page.EmulateMediaOptions().setColorScheme(scheme));
            var violations = new com.deque.html.axecore.playwright.AxeBuilder(page)
                    .withTags(java.util.List.of("wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"))
                    .analyze().getViolations();
            assertThat(violations.stream().map(v -> v.getId() + " " + v.getNodes().stream()
                    .map(n -> String.valueOf(n.getTarget())).toList()).toList()).as(scheme.name()).isEmpty();
        }
    }

    private void slideToTheEnd() {
        var track = slider.boundingBox();
        var y = track.y + track.height / 2;
        touchAlong(track.x + 12, track.x + track.width + 10, y, true);
    }

    private void assertUnlatches(int times) {
        assertThat(nuki.findAll(postRequestedFor(urlPathEqualTo("/smartlock/17/action")))).hasSize(times);
    }
}
