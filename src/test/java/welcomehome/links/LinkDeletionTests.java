package welcomehome.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.time.DayOfWeek;
import java.util.List;
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
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as the locks tests, so they share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class LinkDeletionTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";
    static final String ENDED_YESTERDAY = "now() - interval '1 day'";
    static final String TWO_DAYS_AGO = "now() - interval '2 days'";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;
    UUID lockId;

    @BeforeEach
    void insertALock() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        migrator.sql("DELETE FROM owner").update();
        lockId = data.lock(data.owner(ALICE), 17, "Casa");
    }

    @Test
    void ownerDeletesAnExpiredLinkWithItsScheduleAndItsAttempts() {
        var expired = data.link(lockId, "Carlos", TWO_DAYS_AGO, ENDED_YESTERDAY);
        data.schedule(expired, DayOfWeek.MONDAY, "09:00", "17:00");
        data.attempt(expired, "now() - interval '36 hours'", "OK", "opened", "192.0.2.10");
        var other = data.link(lockId, "Dani", TWO_DAYS_AGO, ENDED_YESTERDAY);
        data.schedule(other, DayOfWeek.MONDAY, "09:00", "17:00");
        data.attempt(other, "now() - interval '36 hours'", "OK", "opened", "192.0.2.11");

        var result = delete(ALICE, expired);

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/locks/" + lockId + "/links");
        assertThat(result.getMvcResult().getFlashMap().get("deletedLabel")).isEqualTo("Carlos");
        assertThat(rows()).isEqualTo(List.of("access_event " + other, "access_link " + other,
                "access_link_schedule " + other));
    }

    @Test
    void aRevokedLinkCanBeDeletedToo() {
        var revoked = data.link(lockId, "Ana", TestData.AN_HOUR_AGO, TestData.IN_A_DAY, "now()");

        assertThat(delete(ALICE, revoked)).hasStatus(HttpStatus.FOUND);

        assertThat(rows()).isEqualTo(List.of());
    }

    /** Deleting never stops a guest: a link that can still open is revoked first. */
    @Test
    void aLinkThatCanStillOpenIsNotDeleted() {
        var active = data.link(lockId, "Ana");
        var pending = data.link(lockId, "Bea", "now() + interval '1 day'", "now() + interval '2 days'");

        assertThat(delete(ALICE, active)).hasStatus(HttpStatus.CONFLICT);
        assertThat(delete(ALICE, pending)).hasStatus(HttpStatus.CONFLICT);

        assertThat(rows()).containsExactlyInAnyOrderElementsOf(List.of("access_link " + active, "access_link " + pending));
    }

    @Test
    void anotherOwnersOrAnUnknownLinkIsNotFound() {
        var bobsLink = data.link(data.lock(data.owner(BOB), 18, "Casa de Bob"), "Invitado de Bob", TWO_DAYS_AGO,
                ENDED_YESTERDAY);

        assertThat(delete(ALICE, bobsLink)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(delete(ALICE, UUID.fromString("0b7a3d2e-6f1c-4a5b-9e8d-1c2b3a4d5e6f")))
                .hasStatus(HttpStatus.NOT_FOUND);

        assertThat(rows()).isEqualTo(List.of("access_link " + bobsLink));
    }

    @Test
    void deletingRequiresTheCsrfToken() {
        var expired = data.link(lockId, "Carlos", TWO_DAYS_AGO, ENDED_YESTERDAY);

        assertThat(mvc.delete().uri("/links/" + expired).with(owner(ALICE))).hasStatus(HttpStatus.FORBIDDEN);

        assertThat(rows()).isEqualTo(List.of("access_link " + expired));
    }

    @Test
    void htmlFormSendsTheDeleteAsAPostWithHiddenMethod() {
        var expired = data.link(lockId, "Carlos", TWO_DAYS_AGO, ENDED_YESTERDAY);

        assertThat(mvc.post().uri("/links/" + expired).with(owner(ALICE)).with(csrf()).param("_method", "delete"))
                .hasStatus(HttpStatus.FOUND);

        assertThat(rows()).isEqualTo(List.of());
    }

    @Test
    void afterDeletingThePageSaysSoAndKeepsTheLinksThatEndedUnfolded() {
        data.link(lockId, "Dani", TWO_DAYS_AGO, ENDED_YESTERDAY);

        assertThat(mvc.get().uri("/locks/" + lockId + "/links").with(owner(ALICE)).flashAttr("deletedLabel", "Carlos"))
                .bodyText().contains("Enlace de Carlos borrado, con sus intentos.", "<details open=\"open\">");
        assertThat(mvc.get().uri("/locks/" + lockId + "/links").with(owner(ALICE)))
                .bodyText().contains("<details>").doesNotContain("<details open");
    }

    private MvcTestResult delete(String email, UUID link) {
        return mvc.delete().uri("/links/" + link).with(owner(email)).with(csrf()).exchange();
    }

    /** Every link, schedule and attempt left, by the link it belongs to. */
    private List<String> rows() {
        return migrator.sql("""
                        SELECT 'access_link ' || id FROM access_link
                        UNION ALL SELECT 'access_link_schedule ' || link_id FROM access_link_schedule
                        UNION ALL SELECT 'access_event ' || link_id FROM access_event
                        ORDER BY 1
                        """)
                .query(String.class)
                .list();
    }

    private static RequestPostProcessor owner(String email) {
        return user(email).roles("OWNER");
    }
}
