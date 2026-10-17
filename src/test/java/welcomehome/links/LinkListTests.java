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
    UUID ownerId;
    UUID lockId;

    @BeforeEach
    void insertALock() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        ownerId = migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id").param(ALICE)
                .query(UUID.class).single();
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
                "Dani Sanz", "Revocado",
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
                .doesNotContain(expired.toString())
                .doesNotContain(revoked.toString());
    }

    @Test
    void validityIsShownInTheLocksTimeZone() {
        migrator.sql("UPDATE lock SET time_zone = 'America/New_York' WHERE id = ?").param(lockId).update();
        insertLink(lockId, "Ana", "'2030-11-01T19:00:00Z'", "'2030-11-03T16:00:00Z'", false);

        assertThat(page()).contains("1/11/2030 15:00 – 3/11/2030 11:00");
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
        return migrator.sql("INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until, revoked_at) "
                        + "VALUES (?, ?, ?, " + validFrom + ", " + validUntil + ", " + (revoked ? "now()" : "NULL")
                        + ") RETURNING id")
                .params(ownerId, lock, label)
                .query(UUID.class)
                .single();
    }

    private UUID insertLock(long nukiSmartlockId, String name) {
        return migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, ?, ?, 'Europe/Madrid') RETURNING id
                        """)
                .params(ownerId, nukiSmartlockId, name)
                .query(UUID.class)
                .single();
    }

    private static RequestPostProcessor owner() {
        return user(ALICE).roles("OWNER");
    }
}
