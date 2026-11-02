package welcomehome.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.admin.InvitationService;

/** webauthn.js and its pages, with Chromium's virtual authenticator, and the scripts of the links page. */
class OwnerPagesTests extends BrowserTest {

    static final String ALICE = "alice@example.com";

    @Autowired
    InvitationService invitations;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;

    @BeforeEach
    void emptyDatabaseAndAnAuthenticator() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        for (var table : List.of("invitation", "user_credentials", "user_entities", "owner")) {
            migrator.sql("DELETE FROM " + table).update();
        }
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
    }

    @Test
    void anOwnerRegistersWithAPasskeySignsOutAndSignsInAgain() {
        register();
        assertThat(page.locator("h1")).hasText("Hola");
        assertThat(passkeys()).isEqualTo(1);

        page.locator("form[action='/logout'] button").click();
        page.waitForURL(url("/login?logout"));
        page.locator("#passkey-login").click();

        page.waitForURL(url("/"));
        assertThat(page.locator("h1")).hasText("Hola");
    }

    @Test
    void aFailedRegistrationSaysSoOnThePage() {
        register();
        page.locator("form[action='/logout'] button").click();
        page.waitForURL(url("/login?logout"));

        page.navigate(invitations.createInvitation(null, "Otra", false).url().toString());
        page.locator("input[name=email]").fill(ALICE);
        page.locator("#registration button[type=submit]").click();

        assertThat(page.locator("#registration-error")).isVisible();
        assertThat(page).hasURL(Pattern.compile("/invite/"));
    }

    @Test
    void anOwnerAddsASecondPasskeyFromTheirSettings() {
        register();
        page.navigate(url("/settings/passkeys"));

        // The authenticator already holds this owner's, and refuses a second: the page says so in its own words.
        page.locator("#add-passkey input[name=label]").fill("Portátil");
        page.locator("#add-passkey button[type=submit]").click();

        assertThat(page.locator("#add-passkey-error")).containsText("ya tiene una passkey");
        assertThat(passkeys()).isEqualTo(1);
    }

    @Test
    void anActionThatAsksFirstDoesNothingIfTheOwnerSaysNo() {
        register();
        openTheLocksLinks();
        createALink();
        var cancel = page.locator("button", new Page.LocatorOptions().setHasText("Anular"));

        page.onceDialog(Dialog::dismiss);
        cancel.click();
        assertThat(cancel).isVisible();
        assertThat(migrator.sql("SELECT count(*) FROM access_link WHERE revoked_at IS NOT NULL")
                .query(Long.class).single()).isZero();

        page.onceDialog(Dialog::accept);
        cancel.click();
        assertThat(page.locator("table.links")).containsText("Anulado");
    }

    @Test
    void aDayNotTickedOffersNeitherItsHoursNorItsWholeDay() {
        register();
        openTheLocksLinks();
        page.locator("details.schedule summary").click();
        var monday = page.locator("input[name=day-MONDAY]");
        var allDay = page.locator("input[name=allday-MONDAY]");
        var opens = page.locator("input[name=opens-MONDAY]");

        assertThat(allDay).isDisabled();
        assertThat(opens).not().isEditable();

        monday.check();

        assertThat(allDay).isEnabled();
        assertThat(opens).isEditable();

        allDay.check();
        monday.uncheck();

        assertThat(allDay).not().isChecked();
        assertThat(allDay).isDisabled();
    }

    @Test
    void theFirstDaysHoursCanBeCopiedToEveryDay() {
        register();
        openTheLocksLinks();
        page.locator("details.schedule summary").click();
        page.locator("input[name=day-TUESDAY]").check();
        page.locator("input[name=opens-TUESDAY]").fill("09:30");
        page.locator("input[name=closes-TUESDAY]").fill("17:45");

        page.locator("[data-copy-schedule]").click();

        assertThat(page.locator("input[name=opens-FRIDAY]")).hasValue("09:30");
        assertThat(page.locator("input[name=closes-MONDAY]")).hasValue("17:45");
    }

    private void register() {
        page.navigate(invitations.createInvitation(null, "Alice", false).url().toString());
        page.locator("input[name=email]").fill(ALICE);
        page.locator("#registration button[type=submit]").click();
        page.waitForURL(url("/"));
    }

    private void openTheLocksLinks() {
        var owner = migrator.sql("SELECT id FROM owner WHERE email = ?").param(ALICE).query(UUID.class).single();
        var lock = data.lock(owner, 17, "Casa");
        page.navigate(url("/locks/" + lock + "/links"));
    }

    private void createALink() {
        page.locator("form#new input[name=label]").fill("Ana");
        page.locator("form#new button[type=submit]").click();
        assertThat(page.locator("table.links")).containsText("Ana");
    }

    private long passkeys() {
        return migrator.sql("SELECT count(*) FROM user_credentials").query(Long.class).single();
    }
}
