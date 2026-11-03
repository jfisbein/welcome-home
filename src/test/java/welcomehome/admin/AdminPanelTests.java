package welcomehome.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.auth.CreatedInvitation;
import welcomehome.auth.InvitationService;
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
    TestData data;
    UUID adminId;
    UUID bobId;

    @BeforeEach
    void insertAnAdminAndAnOwnerWithALockAndALink() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        for (var table : new String[] {"invitation", "user_credentials", "user_entities", "owner"}) {
            migrator.sql("DELETE FROM " + table).update();
        }
        adminId = data.admin(ADMIN_EMAIL);
        bobId = data.owner(OWNER_EMAIL);
        data.link(data.lock(bobId, 1, BOBS_LOCK), BOBS_LINK, "now()", TestData.IN_A_DAY);
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
        assertThat(mvc.post().uri("/admin/recoveries").with(owner()).with(csrf()).param("email", ADMIN_EMAIL))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.delete().uri("/admin/owners").with(owner()).with(csrf()).param("email", ADMIN_EMAIL))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(migrator.sql("SELECT count(*) FROM owner").query(Long.class).single()).isEqualTo(2);
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

    @Test
    void adminCreatesAPasskeyRecoveryLinkForAnOwnerAndSeesItOnce() {
        assertThat(mvc.get().uri("/admin/owners").with(admin())).bodyText().contains("Nueva passkey");

        var result = mvc.post().uri("/admin/recoveries").with(admin()).with(csrf()).param("email", OWNER_EMAIL).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/admin/owners");
        var created = (CreatedInvitation) result.getMvcResult().getFlashMap().get("recovery");
        assertThat(invitations.findRedeemable(tokenOf(created))).hasValueSatisfying(recovery -> {
            assertThat(recovery.recoversOwnerId()).isEqualTo(bobId);
            assertThat(recovery.grantsAdmin()).isFalse();
        });
        assertThat(jdbc.sql("SELECT created_by FROM invitation").query(UUID.class).single()).isEqualTo(adminId);

        assertThat(mvc.get().uri("/admin/owners").with(admin())
                .flashAttr("recovery", created).flashAttr("recoveredEmail", OWNER_EMAIL))
                .bodyText().contains(created.url().toString(), "conservará las que ya tiene");
        assertThat(mvc.get().uri("/admin/owners").with(admin()))
                .bodyText().doesNotContain(created.url().toString());
    }

    @Test
    void recoveryLinkForAnUnknownEmailIsNotCreated() {
        assertThat(mvc.post().uri("/admin/recoveries").with(admin()).with(csrf()).param("email", "nobody@example.com"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isZero();
    }

    @Test
    void creatingARecoveryLinkRequiresTheCsrfToken() {
        assertThat(mvc.post().uri("/admin/recoveries").with(admin()).param("email", OWNER_EMAIL))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminDeletesAnOwnerWithEverythingOfTheirs() {
        data.apiToken(bobId, new byte[] {0});
        migrator.sql("""
                        INSERT INTO access_event (owner_id, link_id, lock_id, result)
                        SELECT owner_id, id, lock_id, 'OK' FROM access_link
                        """).update();
        insertPasskey("YWRtaW4", ADMIN_EMAIL, "YWRtaW4tcGFzc2tleQ");
        insertPasskey("Ym9i", OWNER_EMAIL, "Ym9iLXBhc3NrZXk");
        invitations.createPasskeyRecovery(adminId, bobId);
        migrator.sql("""
                        INSERT INTO invitation (token_hash, note, created_by, expires_at)
                        VALUES ('\\x01', 'Invitada por Bob', ?, now() + interval '1 day')
                        """).param(bobId).update();
        assertThat(mvc.get().uri("/admin/owners").with(admin())).bodyText()
                .contains("data-confirm=\"Se borrarán para siempre la cuenta de bob@example.com", "/js/confirm.js");

        var result = mvc.delete().uri("/admin/owners").with(admin()).with(csrf()).param("email", OWNER_EMAIL).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/admin/owners");
        assertThat(result.getMvcResult().getFlashMap().get("deletedEmail")).isEqualTo(OWNER_EMAIL);
        for (var table : new String[] {"nuki_connection", "lock", "access_link", "access_event"}) {
            assertThat(migrator.sql("SELECT count(*) FROM " + table).query(Long.class).single()).as(table).isZero();
        }
        assertThat(migrator.sql("SELECT email FROM owner").query(String.class).list()).containsExactly(ADMIN_EMAIL);
        assertThat(migrator.sql("SELECT name FROM user_entities").query(String.class).list())
                .containsExactly(ADMIN_EMAIL);
        assertThat(migrator.sql("SELECT credential_id FROM user_credentials").query(String.class).list())
                .containsExactly("YWRtaW4tcGFzc2tleQ");
        assertThat(migrator.sql("SELECT note || ' ' || coalesce(created_by::text, 'nobody') FROM invitation")
                .query(String.class).list())
                .as("his recovery link is gone; an invitation he created stays, by nobody")
                .containsExactly("Invitada por Bob nobody");
    }

    @Test
    void adminSeesTheLinksThatCouldStillBeUsedAndWhomTheyAreFor() {
        invitations.createInvitation(adminId, "Ana, piso de la playa", false);
        invitations.createInvitation(adminId, "Carlos, otro admin", true);
        invitations.createPasskeyRecovery(adminId, bobId);
        invitations.redeem(tokenOf(invitations.createInvitation(adminId, "Ya usada", false)));
        migrator.sql("""
                        INSERT INTO invitation (token_hash, note, created_by, expires_at)
                        VALUES ('\\x02', 'Ya caducada', ?, now() - interval '1 minute')
                        """).param(adminId).update();

        assertThat(pendingSection(mvc.get().uri("/admin/owners").with(admin()).exchange()))
                .contains("Ana, piso de la playa", "Carlos, otro admin", "Invitación de admin", OWNER_EMAIL,
                        "Nueva passkey", "Anular")
                .doesNotContain("Ya usada", "Ya caducada");
    }

    @Test
    void theInvitationPageOnlyInvitesAndTheOwnerListLeadsToItOnce() {
        invitations.createInvitation(adminId, "Zoe, ático", false);

        assertThat(mvc.get().uri("/admin/invitations").with(admin())).hasStatusOk().bodyText()
                .contains("Crear invitación").doesNotContain("Enlaces sin usar", "Zoe, ático");
        var owners = body(mvc.get().uri("/admin/owners").with(admin()).exchange());
        assertThat(owners.split("href=\"/admin/invitations", -1)).as("one link to the invitation page").hasSize(2);
    }

    @Test
    void aCancelledLinkOpensNothingAtOnce() {
        var invitation = invitations.createInvitation(adminId, "Ana", false);
        var recovery = invitations.createPasskeyRecovery(adminId, bobId);
        for (var created : List.of(invitation, recovery)) {
            assertThat(mvc.delete().uri("/admin/invitations/" + created.id()).with(admin()).with(csrf()))
                    .hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/admin/owners#pending");
            assertThat(invitations.findRedeemable(tokenOf(created))).isEmpty();
        }
        assertThat(pendingSection(mvc.get().uri("/admin/owners").with(admin()).flashAttr("cancelledFor", "Ana")
                .exchange())).contains("Enlace para Ana anulado", "No hay invitaciones");
    }

    @Test
    void aLinkAlreadyUsedOrUnknownCannotBeCancelled() {
        var used = invitations.createInvitation(adminId, "Ana", false);
        invitations.redeem(tokenOf(used));

        assertThat(mvc.delete().uri("/admin/invitations/" + used.id()).with(admin()).with(csrf()))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.delete().uri("/admin/invitations/" + UUID.randomUUID()).with(admin()).with(csrf()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void onlyAnAdminCancelsALink() {
        var invitation = invitations.createInvitation(adminId, "Ana", false);

        assertThat(mvc.delete().uri("/admin/invitations/" + invitation.id())
                .with(user(OWNER_EMAIL).roles("OWNER")).with(csrf())).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.delete().uri("/admin/invitations/" + invitation.id()).with(admin()))
                .as("without the CSRF token").hasStatus(HttpStatus.FORBIDDEN);
        assertThat(invitations.findRedeemable(tokenOf(invitation))).isPresent();
    }

    @Test
    void anOwnerDeletedWhileSignedInLosesTheirSessionAndWithItTheirRole() {
        var session = new MockHttpSession();
        assertThat(mvc.get().uri("/admin/owners").with(user(OWNER_EMAIL).roles("OWNER", "ADMIN")).session(session))
                .hasStatusOk();
        assertThat(session.isInvalid()).isFalse();

        assertThat(mvc.delete().uri("/admin/owners").with(admin()).with(csrf()).param("email", OWNER_EMAIL))
                .hasStatus(HttpStatus.FOUND);

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void anAdminCannotDeleteThemselves() {
        assertThat(mvc.get().uri("/admin/owners").with(admin())).bodyText()
                .contains("la cuenta de bob@example.com").doesNotContain("la cuenta de admin@example.com");

        assertThat(mvc.delete().uri("/admin/owners").with(admin()).with(csrf()).param("email", ADMIN_EMAIL))
                .hasStatus(HttpStatus.CONFLICT);
        assertThat(migrator.sql("SELECT count(*) FROM owner").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void deletingAnUnknownOwnerIsNotFound() {
        assertThat(mvc.delete().uri("/admin/owners").with(admin()).with(csrf()).param("email", "nobody@example.com"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void deletingAnOwnerRequiresTheCsrfToken() {
        assertThat(mvc.delete().uri("/admin/owners").with(admin()).param("email", OWNER_EMAIL))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(migrator.sql("SELECT count(*) FROM owner").query(Long.class).single()).isEqualTo(2);
    }

    private void insertPasskey(String userId, String email, String credentialId) {
        data.passkeyUser(userId, email);
        data.passkey(credentialId, userId, "Passkey");
    }

    private MvcTestResult createInvitation(String note, boolean grantsAdmin) {
        return mvc.post().uri("/admin/invitations").with(admin()).with(csrf())
                .param("note", note)
                .param("grantsAdmin", String.valueOf(grantsAdmin))
                .exchange();
    }

    private static String body(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** The owner list's section of unused links, from its heading on. */
    private static String pendingSection(MvcTestResult result) {
        var page = body(result);
        var start = page.indexOf("id=\"pending\"");
        assertThat(start).as("the section of unused links").isNotNegative();
        return page.substring(start);
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
