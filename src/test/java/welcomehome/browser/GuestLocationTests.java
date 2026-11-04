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
import java.util.List;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.Geolocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.crypto.CryptoService;
import welcomehome.crypto.LinkTokenSigner;

/**
 * open.js for a door that checks where its guests are: the position it sends, and the wait and the second slide
 * of a guest asked to confirm or refused as far. Playwright's clock runs the waits without waiting them.
 */
class GuestLocationTests extends BrowserTest {

    static final double DOOR_LATITUDE = 40.416775;
    static final double DOOR_LONGITUDE = -3.703790;
    /** Degrees of latitude per metre. */
    static final double NORTH = 1 / (Math.PI * 6_371_000 / 180);
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
    UUID lockId;
    UUID linkId;
    Locator slider;
    Locator label;
    Locator result;

    @BeforeEach
    void aLinkToALockThatChecks() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        migrator.sql("DELETE FROM owner").update();
        var owner = data.owner("alice@example.com");
        data.apiToken(owner, crypto.encryptAccessToken(owner, "alices-token"));
        lockId = data.lock(owner, 17, "Casa");
        linkId = data.link(lockId, "Ana");
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(204)));
        nuki.stubFor(get(urlPathEqualTo("/smartlock/17/log")).willReturn(okJson("""
                [{"id": "1", "smartlockId": 17, "date": "%s", "action": 3, "trigger": 0, "state": 0}]
                """.formatted(Instant.now().plusSeconds(60)))));
        page.clock().install();
        slider = page.locator("input[type=range]");
        label = page.locator(".slide-label");
        result = page.locator(".open-result");
    }

    @Test
    void aGuestNearTheDoorOpensAtOnce() {
        guestIs(50, "CONFIRM_WHEN_FAR");

        slide();

        assertThat(result).containsText(OPENED);
        assertUnlatches(1);
        assertThat(lastAttempt()).isEqualTo("opened|50");
    }

    @Test
    void aGuestFarAwayReadsWhySlidingWouldOpenWaitsTenSecondsAndOpensBySlidingAgain() {
        guestIs(3000, "CONFIRM_WHEN_FAR");

        slide();

        assertThat(result).containsText("Parece que estás a unos 3 km de la puerta. Si deslizas, la puerta se abrirá"
                + " de verdad aunque no estés delante");
        assertThat(slider).isDisabled();
        assertThat(slider).hasValue("0");
        assertThat(label).hasText("Podrás deslizar en 10 s");
        page.clock().runFor(5_000);
        assertThat(label).hasText("Podrás deslizar en 5 s");
        assertThat(slider).isDisabled();
        page.clock().runFor(5_000);
        assertThat(slider).isEnabled();
        assertThat(label).hasText("Desliza para abrir igualmente");
        assertUnlatches(0);

        slide();

        assertThat(result).containsText(OPENED);
        assertUnlatches(1);
        assertThat(lastAttempt()).isEqualTo("opened|3000");
    }

    /** No permission: the browser does not say where the guest is, and the guest may still open, by confirming. */
    @Test
    void aGuestWhoseBrowserDoesNotSayWhereTheyAreConfirmsAfterTheWait() {
        data.locationCheck(lockId, DOOR_LATITUDE, DOOR_LONGITUDE, "REFUSE_WHEN_FAR", 150);
        context.clearPermissions();
        open();

        slide();

        assertThat(result).containsText("No hemos podido comprobar dónde estás.");
        page.clock().runFor(10_000);
        slide();

        assertThat(result).containsText(OPENED);
        assertThat(lastAttempt()).isEqualTo("opened|-");
    }

    /** The next slide asks the browser afresh: a phone that placed the guest wrong often corrects itself. */
    @Test
    void theStricterModeRefusesAGuestFarAwayAndTheNextSlideAsksWhereTheyAreAgain() {
        guestIs(3000, "REFUSE_WHEN_FAR");

        slide();

        assertThat(result).containsText("Estás a unos 3 km de la puerta, y este enlace solo la abre estando delante.");
        assertThat(slider).isDisabled();
        page.clock().runFor(10_000);
        assertThat(label).hasText("Desliza para abrir");
        assertUnlatches(0);

        context.setGeolocation(new Geolocation(DOOR_LATITUDE + 20 * NORTH, DOOR_LONGITUDE).setAccuracy(15));
        slide();

        assertThat(result).containsText(OPENED);
        assertThat(lastAttempt()).isEqualTo("opened|20");
    }

    /** The lock checks as given, and the guest's browser places them this far north of the door, and allows it. */
    private void guestIs(int metres, String check) {
        data.locationCheck(lockId, DOOR_LATITUDE, DOOR_LONGITUDE, check, 150);
        context.grantPermissions(List.of("geolocation"));
        context.setGeolocation(new Geolocation(DOOR_LATITUDE + metres * NORTH, DOOR_LONGITUDE).setAccuracy(15));
        open();
    }

    private void open() {
        page.navigate(url("/o/" + signer.sign(linkId, 1)));
    }

    /** Four presses of a key open, as a slide to the end does. */
    private void slide() {
        slider.focus();
        for (var press = 0; press < 4; press++) {
            page.keyboard().press("ArrowRight");
        }
    }

    private void assertUnlatches(int times) {
        assertThat(nuki.findAll(postRequestedFor(urlPathEqualTo("/smartlock/17/action")))).hasSize(times);
    }

    /** The latest attempt as "reason|distance", with "-" for none. */
    private String lastAttempt() {
        return migrator.sql("""
                        SELECT reason || '|' || coalesce(distance_m::text, '-') FROM access_event
                        ORDER BY occurred_at DESC LIMIT 1
                        """)
                .query(String.class).single();
    }
}
