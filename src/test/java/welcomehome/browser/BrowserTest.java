package welcomehome.browser;

import java.io.IOException;
import java.net.ServerSocket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import welcomehome.IntegrationTest;
import welcomehome.nuki.NukiStandInConfiguration;

/**
 * What only a browser shows: the app's JavaScript, in Chromium, on a real server, with phone-sized pages that take
 * touches. Nuki is the usual stand-in, so nothing here reaches a real lock. On http://localhost, which browsers treat
 * as secure, so that passkeys work and the session's cookie needs no HTTPS.
 */
@IntegrationTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@Import(NukiStandInConfiguration.class)
abstract class BrowserTest {

    private static final int PORT = freePort();
    private static final String BASE_URL = "http://localhost:" + PORT;

    private static Playwright playwright;
    private static Browser browser;

    BrowserContext context;
    Page page;
    /** A finger stays down within one session of the browser's protocol. */
    private CDPSession input;

    @DynamicPropertySource
    static void serveOnAKnownPort(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("welcomehome.base-url", () -> BASE_URL);
        registry.add("server.servlet.session.cookie.secure", () -> false);
        registry.add("welcomehome.access.rate-limit.per-ip", () -> 1000);
    }

    @BeforeAll
    static void startTheBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void closeTheBrowser() {
        browser.close();
        playwright.close();
    }

    @BeforeEach
    void openAPhoneSizedPage() {
        context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(360, 740).setHasTouch(true).setIsMobile(true).setLocale("es-ES"));
        page = context.newPage();
        input = context.newCDPSession(page);
    }

    @AfterEach
    void closeThePage() {
        context.close();
    }

    static String url(String path) {
        return BASE_URL + path;
    }

    /** A real finger's slide, as Chromium receives it, along a line across the page; it may stay down. */
    void touchAlong(double fromX, double toX, double y, boolean lift) {
        var start = new JsonObject();
        start.addProperty("type", "touchStart");
        start.add("touchPoints", touchPoint(fromX, y));
        input.send("Input.dispatchTouchEvent", start);
        var steps = 12;
        for (var step = 1; step <= steps; step++) {
            var move = new JsonObject();
            move.addProperty("type", "touchMove");
            move.add("touchPoints", touchPoint(fromX + (toX - fromX) * step / steps, y));
            input.send("Input.dispatchTouchEvent", move);
        }
        if (lift) {
            liftTheFinger();
        }
    }

    void liftTheFinger() {
        var end = new JsonObject();
        end.addProperty("type", "touchEnd");
        end.add("touchPoints", new JsonArray());
        input.send("Input.dispatchTouchEvent", end);
    }

    private static JsonArray touchPoint(double x, double y) {
        var point = new JsonObject();
        point.addProperty("x", x);
        point.addProperty("y", y);
        var points = new JsonArray();
        points.add(point);
        return points;
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
