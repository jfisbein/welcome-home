package welcomehome.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.deque.html.axecore.playwright.AxeBuilder;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.auth.InvitationService;
import welcomehome.config.Languages;
import welcomehome.crypto.CryptoService;
import welcomehome.crypto.LinkTokenSigner;

/**
 * axe-core on every page, in every language and in light and dark: WCAG 2.2 level AA is the aim, so a violation fails
 * the build. What axe cannot judge (reading order, a screen reader's announcements) is for a person to try.
 */
class AccessibilityTests extends BrowserTest {

    private static final List<String> TAGS =
            List.of("wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice");

    @Autowired
    InvitationService invitations;

    @Autowired
    LinkTokenSigner signer;

    @Autowired
    CryptoService crypto;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;

    @BeforeEach
    void emptyDatabase() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        for (var table : List.of("invitation", "user_credentials", "user_entities", "owner")) {
            migrator.sql("DELETE FROM " + table).update();
        }
    }

    @Test
    void guestsAndSignedOutPagesHaveNoViolationsInAnyLanguage() {
        var owner = data.owner("alice@example.com");
        data.apiToken(owner, crypto.encryptAccessToken(owner, "alices-token"));
        var lock = data.lock(owner, 17, "Casa");
        var open = "/o/" + signer.sign(data.link(lock, "Ana"), 1);
        var revoked = "/o/" + signer.sign(data.link(lock, "Eva", "now() - interval '2 day'",
                "now() + interval '2 day'", "now()"), 1);
        var invitation = invitations.createInvitation(null, "Bea", false).url().getPath();
        var found = new ArrayList<String>();
        for (var language : Languages.SUPPORTED) {
            context.setExtraHTTPHeaders(Map.of("Accept-Language", language.getLanguage()));
            for (var path : List.of(open, revoked, "/o/invalid", "/login", invitation, "/no-such-page")) {
                scan(path, language.getLanguage(), found);
            }
        }
        assertThat(found).as("violations").isEmpty();
    }

    @Test
    void ownerPagesHaveNoViolationsInAnyLanguage() {
        page.navigate(invitations.createInvitation(null, "Alice", false).url().toString());
        var cdp = context.newCDPSession(page);
        cdp.send("WebAuthn.enable");
        var options = new JsonObject();
        options.addProperty("protocol", "ctap2");
        options.addProperty("transport", "internal");
        options.addProperty("hasResidentKey", true);
        options.addProperty("hasUserVerification", true);
        options.addProperty("isUserVerified", true);
        options.addProperty("automaticPresenceSimulation", true);
        var authenticator = new JsonObject();
        authenticator.add("options", options);
        cdp.send("WebAuthn.addVirtualAuthenticator", authenticator);
        page.locator("input[name=email]").fill("alice@example.com");
        page.locator("input[name=label]").fill("Móvil");
        page.locator("#registration button[type=submit]").click();
        page.waitForURL(url("/"));

        var owner = migrator.sql("SELECT id FROM owner").query(UUID.class).single();
        var lock = data.lock(owner, 17, "Casa");
        var link = data.link(lock, "Ana");
        data.schedule(link, DayOfWeek.MONDAY, "09:00", "17:00");
        data.attempt(link, TestData.AN_HOUR_AGO, "OK", "opened", "203.0.113.5");
        var paths = List.of("/", "/locks", "/locks/" + lock + "/links", "/locks/" + lock + "/history", "/settings",
                "/settings/nuki", "/settings/preferences", "/settings/notifications", "/settings/passkeys",
                "/settings/activity", "/settings/account", "/no-such-page");
        var found = new ArrayList<String>();
        for (var language : Languages.SUPPORTED) {
            migrator.sql("UPDATE owner SET language = ?").param(language.getLanguage()).update();
            for (var path : paths) {
                scan(path, language.getLanguage(), found);
            }
        }
        assertThat(found).as("violations").isEmpty();
    }

    private void scan(String path, String language, List<String> found) {
        for (var scheme : List.of(ColorScheme.LIGHT, ColorScheme.DARK)) {
            page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(scheme));
            page.navigate(url(path));
            var results = new AxeBuilder(page).withTags(TAGS).analyze();
            for (var violation : results.getViolations()) {
                var nodes = violation.getNodes().stream().map(node -> String.valueOf(node.getTarget())).toList();
                found.add("%s [%s, %s] %s: %s %s".formatted(path.replaceAll("[0-9a-f-]{36}", "{id}"), language, scheme,
                        violation.getId(), violation.getHelp(), nodes));
            }
        }
    }
}
