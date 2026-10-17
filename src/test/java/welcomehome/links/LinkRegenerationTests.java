package welcomehome.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.net.URI;
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
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.crypto.LinkTokenSigner;
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as the locks tests, so they share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class LinkRegenerationTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    LinkTokenSigner signer;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID lockId;
    UUID linkId;
    UUID bobsLinkId;

    @BeforeEach
    void insertAnActiveLinkForAliceAndBob() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        lockId = insertLock(ALICE);
        linkId = insertActiveLink(lockId, "Ana");
        bobsLinkId = insertActiveLink(insertLock(BOB), "Invitado de Bob");
    }

    @Test
    void oldUrlStopsWorkingAndTheNewOneWorks() {
        var oldToken = signer.sign(linkId, 1);

        var result = regenerate(ALICE, linkId);

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/locks/" + lockId + "/links");
        var newToken = ((URI) result.getMvcResult().getFlashMap().get("regeneratedUrl")).getPath().substring("/o/".length());
        var currentVersion = jdbc.sql("SELECT token_version FROM resolve_link(?)").param(linkId)
                .query(Integer.class).single();
        assertThat(currentVersion).isEqualTo(2);
        assertThat(signer.verify(oldToken, currentVersion)).as("the old URL").isFalse();
        assertThat(signer.verify(newToken, currentVersion)).as("the new URL").isTrue();
        assertThat(result.getMvcResult().getFlashMap().get("regeneratedLabel")).isEqualTo("Ana");
    }

    @Test
    void datesLabelAndStateStayTheSame() {
        var before = linkWithoutVersion();

        regenerate(ALICE, linkId);

        assertThat(linkWithoutVersion()).isEqualTo(before);
    }

    @Test
    void pageOffersTheNewUrlOnly() {
        var oldUrl = IntegrationTest.BASE_URL + "/o/" + signer.sign(linkId, 1);
        var newUrl = (URI) regenerate(ALICE, linkId).getMvcResult().getFlashMap().get("regeneratedUrl");

        assertThat(mvc.get().uri("/locks/" + lockId + "/links").with(owner(ALICE))
                .flashAttr("regeneratedUrl", newUrl).flashAttr("regeneratedLabel", "Ana"))
                .bodyText().contains("Nuevo enlace para Ana", "El enlace anterior ya no abre la puerta", newUrl.toString())
                .doesNotContain(oldUrl);
    }

    @Test
    void everyRegenerationInvalidatesThePreviousUrl() {
        regenerate(ALICE, linkId);
        var secondToken = signer.sign(linkId, 2);

        regenerate(ALICE, linkId);

        assertThat(signer.verify(secondToken, 3)).isFalse();
        assertThat(migrator.sql("SELECT token_version FROM access_link WHERE id = ?").param(linkId)
                .query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void revokedLinkIsNotRegenerated() {
        migrator.sql("UPDATE access_link SET revoked_at = now() WHERE id = ?").param(linkId).update();

        assertThat(regenerate(ALICE, linkId)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(tokenVersion(linkId)).isEqualTo(1);
    }

    @Test
    void anotherOwnersLinkIsNotFound() {
        assertThat(regenerate(ALICE, bobsLinkId)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(tokenVersion(bobsLinkId)).isEqualTo(1);
    }

    @Test
    void regeneratingRequiresTheCsrfToken() {
        assertThat(mvc.post().uri("/links/" + linkId + "/regenerate").with(owner(ALICE)))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(tokenVersion(linkId)).isEqualTo(1);
    }

    private MvcTestResult regenerate(String email, UUID link) {
        return mvc.post().uri("/links/" + link + "/regenerate").with(owner(email)).with(csrf()).exchange();
    }

    private int tokenVersion(UUID link) {
        return migrator.sql("SELECT token_version FROM access_link WHERE id = ?").param(link)
                .query(Integer.class).single();
    }

    private String linkWithoutVersion() {
        return migrator.sql("SELECT concat_ws('|', lock_id, label, valid_from, valid_until, revoked_at) FROM access_link WHERE id = ?")
                .param(linkId).query(String.class).single();
    }

    private UUID insertLock(String email) {
        var ownerId = migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id").param(email)
                .query(UUID.class).single();
        return migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, 17, 'Casa', 'Europe/Madrid') RETURNING id
                        """)
                .param(ownerId).query(UUID.class).single();
    }

    private UUID insertActiveLink(UUID lock, String label) {
        return migrator.sql("""
                        INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until)
                        SELECT owner_id, id, ?, now() - interval '1 hour', now() + interval '1 day' FROM lock WHERE id = ?
                        RETURNING id
                        """)
                .params(label, lock).query(UUID.class).single();
    }

    private static RequestPostProcessor owner(String email) {
        return user(email).roles("OWNER");
    }
}
