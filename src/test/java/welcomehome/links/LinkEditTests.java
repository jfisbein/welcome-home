package welcomehome.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

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
class LinkEditTests {

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
    void insertALinkForAliceAndBob() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        lockId = insertLock(ALICE, "America/New_York");
        linkId = insertLink(lockId, "Ana", "'2030-11-01T19:00:00Z'", "'2030-11-03T16:00:00Z'");
        bobsLinkId = insertLink(insertLock(BOB, "Europe/Madrid"), "Invitado de Bob", "now()", "now() + interval '1 day'");
    }

    @Test
    void editPageShowsTheLinkInTheLocksTimeZone() {
        assertThat(mvc.get().uri("/links/" + linkId).with(owner(ALICE)))
                .hasStatusOk()
                .bodyText().contains("Enlace de Ana", "2030-11-01T15:00", "2030-11-03T11:00", "America/New_York");
        assertThat(mvc.get().uri("/locks/" + lockId + "/links").with(owner(ALICE)))
                .bodyText().contains("/links/" + linkId);
    }

    @Test
    void ownerChangesLabelAndDatesAndTheUrlStaysTheSame() {
        var result = update(ALICE, linkId, " Ana y Luis ", "2030-11-02T10:00", "2030-11-05T12:00");

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/locks/" + lockId + "/links");
        assertThat(result.getMvcResult().getFlashMap().get("updatedLabel")).isEqualTo("Ana y Luis");
        assertThat(migrator.sql("""
                        SELECT label || '|' || to_char(valid_from AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') || '|'
                               || to_char(valid_until AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') || '|' || token_version
                        FROM access_link WHERE id = ?
                        """)
                .param(linkId).query(String.class).single())
                .as("New York leaves daylight saving time on 3 November 2030: UTC-4 before, UTC-5 after")
                .isEqualTo("Ana y Luis|2030-11-02 14:00|2030-11-05 17:00|1");
        assertThat(signer.verify(signer.sign(linkId, 1), currentTokenVersion(linkId))).isTrue();
    }

    @Test
    void expiredLinkCanBeExtended() {
        migrator.sql("UPDATE access_link SET valid_from = now() - interval '3 days', valid_until = now() - interval '1 day'")
                .update();

        assertThat(update(ALICE, linkId, "Ana", "2020-01-01T00:00", "2030-11-05T12:00")).hasStatus(HttpStatus.FOUND);

        assertThat(migrator.sql("SELECT valid_until > now() FROM access_link WHERE id = ?").param(linkId)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void invalidInputChangesNothing() {
        var before = row(linkId);

        for (var input : new String[][] {
                {"", "2030-11-02T10:00", "2030-11-05T12:00", "Escribe para quién es el enlace"},
                {"Ana", "", "2030-11-05T12:00", "Indica la fecha y hora de inicio"},
                {"Ana", "2030-11-05T12:00", "2030-11-02T10:00", "El fin tiene que ser posterior al inicio"},
                {"Ana", "2020-11-02T10:00", "2020-11-05T12:00", "El fin ya ha pasado"}}) {
            assertThat(update(ALICE, linkId, input[0], input[1], input[2]))
                    .as(input[3])
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyText().contains(input[3]);
        }

        assertThat(row(linkId)).isEqualTo(before);
    }

    @Test
    void revokedLinkCannotBeEdited() {
        migrator.sql("UPDATE access_link SET revoked_at = now() WHERE id = ?").param(linkId).update();
        var before = row(linkId);

        assertThat(mvc.get().uri("/links/" + linkId).with(owner(ALICE))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(update(ALICE, linkId, "Ana", "2030-11-02T10:00", "2030-11-05T12:00")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(row(linkId)).isEqualTo(before);
    }

    @Test
    void anotherOwnersLinkIsNotFound() {
        var before = row(bobsLinkId);

        assertThat(mvc.get().uri("/links/" + bobsLinkId).with(owner(ALICE))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(update(ALICE, bobsLinkId, "Mío", "2030-11-02T10:00", "2030-11-05T12:00"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(row(bobsLinkId)).isEqualTo(before);
    }

    @Test
    void htmlFormSendsThePatchAsAPostWithHiddenMethod() {
        assertThat(mvc.post().uri("/links/" + linkId).with(owner(ALICE)).with(csrf()).param("_method", "patch")
                .param("label", "Luis").param("validFrom", "2030-11-02T10:00").param("validUntil", "2030-11-05T12:00"))
                .hasStatus(HttpStatus.FOUND);
        assertThat(migrator.sql("SELECT label FROM access_link WHERE id = ?").param(linkId)
                .query(String.class).single()).isEqualTo("Luis");
    }

    @Test
    void editingRequiresTheCsrfToken() {
        var before = row(linkId);

        assertThat(mvc.patch().uri("/links/" + linkId).with(owner(ALICE))
                .param("label", "Luis").param("validFrom", "2030-11-02T10:00").param("validUntil", "2030-11-05T12:00"))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(row(linkId)).isEqualTo(before);
    }

    private MvcTestResult update(String email, UUID link, String label, String validFrom, String validUntil) {
        return mvc.patch().uri("/links/" + link).with(owner(email)).with(csrf())
                .param("label", label).param("validFrom", validFrom).param("validUntil", validUntil)
                .exchange();
    }

    private int currentTokenVersion(UUID link) {
        return jdbc.sql("SELECT token_version FROM resolve_link(?)").param(link).query(Integer.class).single();
    }

    private String row(UUID link) {
        return migrator.sql("SELECT access_link::text FROM access_link WHERE id = ?").param(link)
                .query(String.class).single();
    }

    private UUID insertLock(String email, String timeZone) {
        var ownerId = migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id").param(email)
                .query(UUID.class).single();
        return migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, 17, 'Casa', ?) RETURNING id
                        """)
                .params(ownerId, timeZone).query(UUID.class).single();
    }

    private UUID insertLink(UUID lock, String label, String validFrom, String validUntil) {
        return migrator.sql("INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until) "
                        + "SELECT owner_id, id, ?, " + validFrom + ", " + validUntil + " FROM lock WHERE id = ? RETURNING id")
                .params(label, lock).query(UUID.class).single();
    }

    private static RequestPostProcessor owner(String email) {
        return user(email).roles("OWNER");
    }
}
