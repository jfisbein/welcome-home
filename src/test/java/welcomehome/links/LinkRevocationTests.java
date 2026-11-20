package welcomehome.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.SignedIn;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as the locks tests, so they share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class LinkRevocationTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;
    UUID lockId;
    UUID linkId;
    UUID bobsLinkId;

    @BeforeEach
    void insertAnActiveLinkForAliceAndBob() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        data.deleteOwners();
        lockId = insertLock(ALICE);
        linkId = data.link(lockId, "Ana");
        bobsLinkId = data.link(insertLock(BOB), "Invitado de Bob");
    }

    @Test
    void revokedLinkOpensNothingFromNowOn() {
        var result = revoke(ALICE, linkId);

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/locks/" + lockId + "/links");
        assertThat(result.getMvcResult().getFlashMap().get("revokedLabel")).isEqualTo("Ana");
        var resolved = jdbc.sql("SELECT valid_from, valid_until, revoked_at FROM resolve_link(?)")
                .param(linkId).query(ResolvedDates.class).single();
        assertThat(resolved.revokedAt()).as("the guest flow sees the revocation").isNotNull();
        assertThat(LinkStatus.of(resolved.validFrom().toInstant(), resolved.validUntil().toInstant(),
                resolved.revokedAt().toInstant(), false, Instant.now()))
                .isEqualTo(LinkStatus.REVOKED);
    }

    @Test
    void pageShowsTheRevocationAndNoLongerOffersTheLink() {
        revoke(ALICE, linkId);

        assertThat(mvc.get().uri("/locks/" + lockId + "/links").with(SignedIn.owner(ALICE)).flashAttr("revokedLabel", "Ana"))
                .bodyText().contains("Enlace «Ana» anulado", "Anulado")
                .doesNotContain("Copiar", "/links/" + linkId + "/revoke", "Sustituir enlace");
    }

    @Test
    void revokingAgainKeepsTheFirstRevocation() {
        revoke(ALICE, linkId);
        var first = revokedAt(linkId);

        assertThat(revoke(ALICE, linkId)).hasStatus(HttpStatus.FOUND);

        assertThat(revokedAt(linkId)).isEqualTo(first);
    }

    @Test
    void anotherOwnersLinkIsNotFound() {
        assertThat(revoke(ALICE, bobsLinkId)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(revokedAt(bobsLinkId)).isNull();
    }

    @Test
    void revokingRequiresTheCsrfToken() {
        assertThat(mvc.post().uri("/links/" + linkId + "/revoke").with(SignedIn.owner(ALICE))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(revokedAt(linkId)).isNull();
    }

    private MvcTestResult revoke(String email, UUID link) {
        return mvc.post().uri("/links/" + link + "/revoke").with(SignedIn.owner(email)).with(csrf()).exchange();
    }

    private OffsetDateTime revokedAt(UUID link) {
        return migrator.sql("SELECT revoked_at FROM access_link WHERE id = ?").param(link)
                .query(OffsetDateTime.class).optional().orElse(null);
    }

    private UUID insertLock(String email) {
        return data.lock(data.owner(email), 17, "Casa");
    }

    record ResolvedDates(OffsetDateTime validFrom, OffsetDateTime validUntil, OffsetDateTime revokedAt) {
    }
}
