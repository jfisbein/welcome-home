package welcomehome.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.crypto.LinkTokenSigner;
import welcomehome.nuki.LockProvider;

@IntegrationTest
@AutoConfigureMockMvc
class GuestPageTests {

    static final String OPEN_BUTTON = "id=\"open\"";
    static final String NOT_YET_VALID = "Este enlace todavía no es válido.";
    static final String EXPIRED = "Este enlace ha caducado.";
    static final String REVOKED = "Este enlace se ha revocado.";
    static final String UNAVAILABLE = "Esta puerta no está disponible ahora mismo.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    LinkTokenSigner signer;

    @Autowired
    PostgreSQLContainer postgres;

    /** Every GET below must leave it untouched: the guest page never acts on a lock. */
    @MockitoSpyBean
    LockProvider lockProvider;

    JdbcClient migrator;
    UUID lockId;

    @BeforeEach
    void insertAConnectedLock() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        var ownerId = migrator.sql("INSERT INTO owner (email) VALUES ('alice@example.com') RETURNING id")
                .query(UUID.class).single();
        lockId = migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, 17, 'Casa de Alice', 'America/New_York') RETURNING id
                        """)
                .param(ownerId).query(UUID.class).single();
        // The page reads only the connection's status: the token is never decrypted.
        migrator.sql("INSERT INTO nuki_connection (owner_id, api_token_encrypted, status) VALUES (?, '\\x00', 'OK')")
                .param(ownerId).update();
    }

    @AfterEach
    void getNeverCallsTheLockProvider() {
        verifyNoInteractions(lockProvider);
    }

    @Test
    void activeLinkShowsTheLockItsValidityAndTheOpenButton() {
        var link = insertLink("'2020-07-01T19:00:00Z'", "'2040-07-03T15:00:00Z'", false);

        var result = guestPage(signer.sign(link, 1));

        assertThat(result).hasStatusOk()
                .headers().hasValue("X-Robots-Tag", "noindex, nofollow");
        assertThat(body(result))
                .contains("Casa de Alice", "del 01/07/2020 15:00 al 03/07/2040 11:00 (hora de America/New_York)",
                        OPEN_BUTTON, "<meta name=\"robots\" content=\"noindex, nofollow\">")
                .doesNotContain("Etiqueta privada");
    }

    @Test
    void pendingLinkSaysWhenItStarts() {
        var link = insertLink("'2040-11-01T19:00:00Z'", "'2040-11-03T16:00:00Z'", false);

        assertThat(body(guestPage(signer.sign(link, 1))))
                .contains(NOT_YET_VALID, "a partir del 01/11/2040 15:00")
                .doesNotContain(OPEN_BUTTON);
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

        migrator.sql("UPDATE lock SET active = false").update();
        assertUnavailable(token);

        migrator.sql("UPDATE lock SET active = true").update();
        migrator.sql("UPDATE nuki_connection SET status = 'DISCONNECTED'").update();
        assertUnavailable(token);

        migrator.sql("DELETE FROM nuki_connection").update();
        assertUnavailable(token);
    }

    @Test
    void unavailabilityDoesNotHideTheLinksOwnState() {
        migrator.sql("DELETE FROM nuki_connection").update();
        var expired = insertLink("now() - interval '2 days'", "now() - interval '1 day'", false);

        assertThat(body(guestPage(signer.sign(expired, 1)))).contains(EXPIRED).doesNotContain(UNAVAILABLE);
    }

    @Test
    void openButtonShowsTheSameMessagesAsThePage() {
        var link = insertLink("now() - interval '1 day'", "now() + interval '1 day'", false);

        assertThat(body(guestPage(signer.sign(link, 1)))).as("what the button shows, which the page carries")
                .contains(NOT_YET_VALID, EXPIRED, REVOKED, UNAVAILABLE);
        var script = mvc.get().uri("/js/open.js").exchange();
        assertThat(script).hasStatusOk();
        assertThat(body(script)).as("the page carries no CSRF token to send").doesNotContain("_csrf");
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

    private void assertUnavailable(String token) {
        assertThat(body(guestPage(token))).contains(UNAVAILABLE).doesNotContain(OPEN_BUTTON);
    }

    private MvcTestResult guestPage(String token) {
        return mvc.get().uri("/o/" + token).exchange();
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private UUID insertLink(String validFrom, String validUntil, boolean revoked) {
        return migrator.sql("INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until, revoked_at) "
                        + "SELECT owner_id, id, 'Etiqueta privada', " + validFrom + ", " + validUntil + ", "
                        + (revoked ? "now()" : "NULL") + " FROM lock WHERE id = ? RETURNING id")
                .param(lockId).query(UUID.class).single();
    }
}
