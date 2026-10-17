package welcomehome.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.db.OwnerTransactions;

@IntegrationTest
@AutoConfigureMockMvc
class AdminPanelTests {

    static final String ADMIN_EMAIL = "admin@example.com";
    static final String OWNER_EMAIL = "bob@example.com";
    static final String BOBS_LOCK = "Casa de Bob";
    static final String BOBS_LINK = "Airbnb de Bob";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    InvitationService invitations;

    @Autowired
    OwnerTransactions ownerTransactions;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID adminId;

    @BeforeEach
    void insertAnAdminAndAnOwnerWithALockAndALink() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        for (var table : new String[] {"invitation", "user_credentials", "user_entities", "owner"}) {
            migrator.sql("DELETE FROM " + table).update();
        }
        adminId = migrator.sql("INSERT INTO owner (email, is_admin) VALUES (?, true) RETURNING id")
                .param(ADMIN_EMAIL)
                .query(UUID.class)
                .single();
        var bobId = migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id")
                .param(OWNER_EMAIL)
                .query(UUID.class)
                .single();
        var lockId = migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, 1, ?, 'Europe/Madrid')
                        RETURNING id
                        """)
                .params(bobId, BOBS_LOCK)
                .query(UUID.class)
                .single();
        migrator.sql("""
                        INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until)
                        VALUES (?, ?, ?, now(), now() + interval '1 day')
                        """)
                .params(bobId, lockId, BOBS_LINK)
                .update();
    }

    @Test
    void anonymousVisitorIsSentToLogin() {
        assertThat(mvc.get().uri("/admin/owners"))
                .hasStatus(HttpStatus.FOUND)
                .redirectedUrl().endsWith("/login");
    }

    @Test
    void ownerWhoIsNotAdminCannotOpenTheAdminPanel() {
        assertThat(mvc.get().uri("/admin/owners").with(owner())).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/admin/invitations").with(owner())).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.post().uri("/admin/invitations").with(owner()).with(csrf()).param("note", "Carol"))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isZero();
    }

    @Test
    void onlyAdminsSeeTheLinkToTheAdminPanel() {
        assertThat(mvc.get().uri("/").with(admin())).bodyText().contains("Administración");
        assertThat(mvc.get().uri("/").with(owner())).bodyText().doesNotContain("Administración");
    }

    @Test
    void adminSeesEveryOwner() {
        assertThat(mvc.get().uri("/admin/owners").with(admin()))
                .hasStatusOk()
                .bodyText().contains(ADMIN_EMAIL, OWNER_EMAIL);
    }

    @Test
    void adminDoesNotSeeOtherOwnersLocksOrLinks() {
        for (var page : new String[] {"/", "/admin/owners", "/admin/invitations"}) {
            assertThat(mvc.get().uri(page).with(admin()))
                    .as(page)
                    .bodyText().doesNotContain(BOBS_LOCK, BOBS_LINK);
        }
        // Being an admin gives no access to other owners' rows: Row-Level Security knows no admins.
        ownerTransactions.run(adminId, () -> {
            assertThat(jdbc.sql("SELECT count(*) FROM lock").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM access_link").query(Long.class).single()).isZero();
        });
    }

    @Test
    void adminCreatesAnInvitationAndSeesItsLinkOnce() {
        var result = createInvitation("Carol", false);

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/admin/invitations");
        var created = (CreatedInvitation) result.getMvcResult().getFlashMap().get("invitation");
        assertThat(created.url().toString()).startsWith(IntegrationTest.BASE_URL + "/invite/");
        assertThat(invitations.findRedeemable(tokenOf(created)))
                .hasValueSatisfying(invitation -> assertThat(invitation.grantsAdmin()).isFalse());
        assertThat(jdbc.sql("SELECT created_by FROM invitation WHERE note = 'Carol'").query(UUID.class).single())
                .isEqualTo(adminId);

        assertThat(mvc.get().uri("/admin/invitations").with(admin()).flashAttr("invitation", created))
                .bodyText().contains(created.url().toString());
        assertThat(mvc.get().uri("/admin/invitations").with(admin()))
                .bodyText().doesNotContain(created.url().toString());
    }

    @Test
    void adminCanInviteAnotherAdmin() {
        var created = (CreatedInvitation) createInvitation("Dave", true).getMvcResult().getFlashMap().get("invitation");

        assertThat(invitations.findRedeemable(tokenOf(created)))
                .hasValueSatisfying(invitation -> assertThat(invitation.grantsAdmin()).isTrue());
    }

    @Test
    void invitationNeedsANote() {
        for (var note : new String[] {"", "   ", "x".repeat(101)}) {
            assertThat(mvc.post().uri("/admin/invitations").with(admin()).with(csrf()).param("note", note))
                    .as(note)
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyText().contains("Escribe para quién es la invitación");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isZero();
    }

    @Test
    void creatingAnInvitationRequiresTheCsrfToken() {
        assertThat(mvc.post().uri("/admin/invitations").with(admin()).param("note", "Carol"))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    private MvcTestResult createInvitation(String note, boolean grantsAdmin) {
        return mvc.post().uri("/admin/invitations").with(admin()).with(csrf())
                .param("note", note)
                .param("grantsAdmin", String.valueOf(grantsAdmin))
                .exchange();
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN_EMAIL).roles("OWNER", "ADMIN");
    }

    private static RequestPostProcessor owner() {
        return user(OWNER_EMAIL).roles("OWNER");
    }

    private static String tokenOf(CreatedInvitation invitation) {
        return invitation.url().getPath().substring("/invite/".length());
    }
}
