package welcomehome.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.SignedIn;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.nuki.NukiStandInConfiguration;

/**
 * A link paused and resumed. Same configuration as the locks tests, so they share one application context. That a
 * paused link opens no door is in {@code OpenDoorTests}, and what its guest reads in {@code GuestPageTests}.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class LinkPauseTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OpenLinks openLinks;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;
    UUID aliceId;
    UUID lockId;
    UUID linkId;
    UUID bobsLinkId;

    @BeforeEach
    void insertAnActiveLinkForAliceAndBob() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        data.deleteOwners();
        aliceId = data.owner(ALICE);
        lockId = data.lock(aliceId, 17, "Casa");
        linkId = data.link(lockId, "Ana");
        bobsLinkId = data.link(data.lock(data.owner(BOB), 18, "Casa"), "Invitado de Bob");
    }

    /**
     * What closes the link is its revocation, which everything that refuses a link already looks at, a release
     * from before pauses included: rolled back to, it reads the link as revoked and opens nothing with it.
     */
    @Test
    void aPausedLinkIsARevokedOneMarkedAsOneItsOwnerMayResume() {
        assertThat(openLinks.countByLock(aliceId)).isEqualTo(Map.of(lockId, 1));

        var result = post(ALICE, linkId, "pause");

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/locks/" + lockId + "/links");
        assertThat(result.getMvcResult().getFlashMap().get("pausedLabel")).isEqualTo("Ana");
        assertThat(state(linkId)).isEqualTo("revoked|paused");
        assertThat(jdbc.sql("SELECT revoked_at IS NOT NULL FROM resolve_link(?)").param(linkId).query(Boolean.class)
                .single()).as("the guest flow sees a revoked link").isTrue();
        assertThat(openLinks.countByLock(aliceId)).as("it is not among those that can open now").isEmpty();
    }

    @Test
    void aResumedLinkIsAsItWasWithTheSameUrl() {
        var version = tokenVersion(linkId);
        post(ALICE, linkId, "pause");

        var result = post(ALICE, linkId, "resume");

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/locks/" + lockId + "/links");
        assertThat(result.getMvcResult().getFlashMap().get("resumedLabel")).isEqualTo("Ana");
        assertThat(state(linkId)).isEqualTo("open|-");
        assertThat(tokenVersion(linkId)).as("the URL its guest has still works").isEqualTo(version);
        assertThat(openLinks.countByLock(aliceId)).isEqualTo(Map.of(lockId, 1));
    }

    @Test
    void thePageSaysItIsPausedAndOffersOnlyToResumeOrRevokeIt() {
        assertThat(page(null)).contains("/links/" + linkId + "/pause", "Pausar").doesNotContain("En pausa", "Reanudar");

        post(ALICE, linkId, "pause");

        assertThat(page("pausedLabel")).contains("Enlace «Ana» en pausa: no abre la puerta hasta que lo reanudes.",
                        "<strong>En pausa</strong>", "/links/" + linkId + "/resume", "Reanudar",
                        "El enlace «Ana» volverá a abrir la puerta en sus fechas. ¿Reanudarlo?",
                        "/links/" + linkId + "/revoke", "/history?link=" + linkId)
                .as("among the links that can open, not folded away with those that ended")
                .doesNotContain("Caducados y anulados", "Ningún enlace de esta cerradura")
                .as("nothing that gives the link away, edits it or deletes it")
                .doesNotContain("Copiar", "href=\"/links/" + linkId + "\"", "/links/" + linkId + "/regenerate",
                        "/links/" + linkId + "/pause", "Borrar");
        assertThat(page("resumedLabel")).contains("Enlace «Ana» reanudado: vuelve a abrir la puerta en sus fechas.");
    }

    @Test
    void aPausedLinkIsNeitherEditedNorReplacedNorDeleted() {
        post(ALICE, linkId, "pause");

        assertThat(mvc.get().uri("/links/" + linkId).with(owner(ALICE))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(post(ALICE, linkId, "regenerate")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.delete().uri("/links/" + linkId).with(owner(ALICE)).with(csrf())).hasStatus(HttpStatus.CONFLICT);
        assertThat(state(linkId)).isEqualTo("revoked|paused");
    }

    @Test
    void revokingAPausedLinkIsForGood() {
        post(ALICE, linkId, "pause");

        assertThat(post(ALICE, linkId, "revoke")).hasStatus(HttpStatus.FOUND);

        assertThat(state(linkId)).isEqualTo("revoked|-");
        assertThat(post(ALICE, linkId, "resume")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(state(linkId)).isEqualTo("revoked|-");
    }

    @Test
    void onlyALinkThatCouldStillOpenIsPausedAndOnlyAPausedOneResumed() {
        var revoked = data.link(lockId, "Bea", TestData.AN_HOUR_AGO, TestData.IN_A_DAY, "now()");
        var expired = data.link(lockId, "Carlos", "now() - interval '2 days'", TestData.AN_HOUR_AGO);

        assertThat(post(ALICE, revoked, "pause")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(post(ALICE, expired, "pause")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(post(ALICE, revoked, "resume")).as("a revoked link stays revoked").hasStatus(HttpStatus.NOT_FOUND);
        assertThat(post(ALICE, linkId, "resume")).as("one that is not paused").hasStatus(HttpStatus.NOT_FOUND);
        assertThat(state(revoked)).isEqualTo("revoked|-");
        assertThat(state(expired)).isEqualTo("open|-");
        assertThat(state(linkId)).isEqualTo("open|-");

        post(ALICE, linkId, "pause");
        assertThat(post(ALICE, linkId, "pause")).as("already paused").hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aPausedLinkPastItsEndHasExpiredAndCanBeDeleted() {
        var link = data.link(lockId, "Dani", "now() - interval '2 days'", TestData.AN_HOUR_AGO);
        data.pause(link);

        assertThat(page(null)).contains("Dani", "Caducado");
        assertThat(mvc.delete().uri("/links/" + link).with(owner(ALICE)).with(csrf())).hasStatus(HttpStatus.FOUND);
    }

    @Test
    void anotherOwnersLinkIsNotFound() {
        assertThat(post(ALICE, bobsLinkId, "pause")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(state(bobsLinkId)).isEqualTo("open|-");
        data.pause(bobsLinkId);

        assertThat(post(ALICE, bobsLinkId, "resume")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(state(bobsLinkId)).isEqualTo("revoked|paused");
    }

    @Test
    void pausingAndResumingRequireTheCsrfToken() {
        assertThat(mvc.post().uri("/links/" + linkId + "/pause").with(owner(ALICE))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(state(linkId)).isEqualTo("open|-");
        data.pause(linkId);

        assertThat(mvc.post().uri("/links/" + linkId + "/resume").with(owner(ALICE))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(state(linkId)).isEqualTo("revoked|paused");
    }

    private MvcTestResult post(String email, UUID link, String action) {
        return mvc.post().uri("/links/" + link + "/" + action).with(owner(email)).with(csrf()).exchange();
    }

    private String page(String flash) {
        var request = mvc.get().uri("/locks/" + lockId + "/links").with(owner(ALICE));
        var result = (flash == null ? request : request.flashAttr(flash, "Ana")).exchange();
        assertThat(result).hasStatusOk();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private String state(UUID link) {
        return migrator.sql("""
                        SELECT CASE WHEN revoked_at IS NULL THEN 'open' ELSE 'revoked' END || '|'
                               || CASE WHEN paused_at IS NULL THEN '-' ELSE 'paused' END
                        FROM access_link WHERE id = ?
                        """).param(link).query(String.class).single();
    }

    private int tokenVersion(UUID link) {
        return migrator.sql("SELECT token_version FROM access_link WHERE id = ?").param(link).query(Integer.class)
                .single();
    }

    private static RequestPostProcessor owner(String email) {
        return SignedIn.owner(email);
    }
}
