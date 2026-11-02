package welcomehome.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.crypto.LinkTokenSigner;
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as the locks tests, so they share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class LinkListTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    LinkTokenSigner signer;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;
    UUID ownerId;
    UUID lockId;

    @BeforeEach
    void insertALock() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        migrator.sql("DELETE FROM owner").update();
        ownerId = data.owner(ALICE);
        lockId = insertLock(17, "Casa");
    }

    @Test
    void listsEveryLinkWithItsComputedStateUsableOnesFirst() {
        insertLink(lockId, "Ana Pérez", "now() + interval '1 day'", "now() + interval '2 days'", false);
        insertLink(lockId, "Bea Ruiz", "now() - interval '1 day'", "now() + interval '1 day'", false);
        insertLink(lockId, "Carlos Gil", "now() - interval '2 days'", "now() - interval '1 day'", false);
        insertLink(lockId, "Dani Sanz", "now() - interval '1 day'", "now() + interval '1 day'", true);

        assertThat(page()).containsSubsequence(
                "Ana Pérez", "Pendiente",
                "Bea Ruiz", "Activo",
                "Dani Sanz", "Anulado",
                "Carlos Gil", "Caducado");
    }

    @Test
    void usableLinksCanBeCopiedAndSharedWithTheUrlOfTheirCurrentTokenVersion() {
        var active = insertLink(lockId, "Bea", "now() - interval '1 day'", "now() + interval '1 day'", false);
        migrator.sql("UPDATE access_link SET token_version = 3 WHERE id = ?").param(active).update();
        var expired = insertLink(lockId, "Carlos", "now() - interval '2 days'", "now() - interval '1 day'", false);
        var revoked = insertLink(lockId, "Dani", "now() - interval '1 day'", "now() + interval '1 day'", true);

        var body = page();

        var url = IntegrationTest.BASE_URL + "/o/" + signer.sign(active, 3);
        assertThat(body).contains("data-copy-text=\"" + url + "\"", "data-share-url=\"" + url + "\"")
                .doesNotContain(signer.sign(active, 1))
                .as("an expired link can be edited, to extend it, but not copied")
                .doesNotContain("/o/" + expired)
                .contains("/links/" + expired + "\"")
                .as("a revoked link is offered for nothing but its history")
                .doesNotContain("/links/" + revoked, signer.sign(revoked, 1))
                .contains("/history?link=" + revoked + "\"");
    }

    @Test
    void regeneratingOrRevokingAsksFirstWhatItDoes() {
        insertLink(lockId, "Bea", "now() - interval '1 day'", "now() + interval '1 day'", false);

        assertThat(page()).contains(
                "data-confirm=\"El enlace actual de Bea dejará de funcionar ahora mismo",
                "data-confirm=\"El enlace de Bea dejará de abrir la puerta para siempre",
                "/js/confirm.js");
    }

    @Test
    void whatTheActionsDoIsSaidOnThePageOnlyWhileALinkHasThem() {
        var hint = "«Anular» hace que deje de abrir la puerta para siempre";
        insertLink(lockId, "Ana", "now() - interval '2 days'", "now() - interval '1 day'", false);
        assertThat(page()).doesNotContain(hint);

        insertLink(lockId, "Bea", "now()", "now() + interval '1 day'", false);
        assertThat(page()).contains(hint);
    }

    @Test
    void theFormToCreateALinkComesBeforeTheList() {
        insertLink(lockId, "Ana", "now()", "now() + interval '1 day'", false);

        assertThat(page()).containsSubsequence("<form id=\"new\"", "Crear enlace", "<table class=\"links\"", "Ana");
    }

    @Test
    void validityIsShownInTheLocksTimeZone() {
        migrator.sql("UPDATE lock SET time_zone = 'America/New_York' WHERE id = ?").param(lockId).update();
        insertLink(lockId, "Ana", "'2030-11-01T19:00:00Z'", "'2030-11-03T16:00:00Z'", false);

        assertThat(page()).contains("01/11/2030 15:00 – 03/11/2030 11:00");
    }

    @Test
    void showsOnlyThisLocksLinks() {
        var otherLock = insertLock(18, "Garaje");
        insertLink(otherLock, "Enlace del garaje", "now()", "now() + interval '1 day'", false);

        assertThat(page()).contains("Esta cerradura todavía no tiene enlaces").doesNotContain("Enlace del garaje");
    }

    private String page() {
        var result = mvc.get().uri("/locks/" + lockId + "/links").with(owner()).exchange();
        assertThat(result).hasStatusOk();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private UUID insertLink(UUID lock, String label, String validFrom, String validUntil, boolean revoked) {
        return data.link(lock, label, validFrom, validUntil, revoked ? "now()" : null);
    }

    private UUID insertLock(long nukiSmartlockId, String name) {
        return data.lock(ownerId, nukiSmartlockId, name);
    }

    private static RequestPostProcessor owner() {
        return user(ALICE).roles("OWNER");
    }
}
