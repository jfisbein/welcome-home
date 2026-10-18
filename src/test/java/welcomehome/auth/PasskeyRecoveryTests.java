package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.admin.InvitationService;

/** Alice registered with a passkey on one device, lost it, and gets a recovery link for a new one. */
@IntegrationTest
@AutoConfigureMockMvc
class PasskeyRecoveryTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    InvitationService invitations;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    final PasskeyEmulator oldDevice = new PasskeyEmulator(IntegrationTest.BASE_URL);
    final PasskeyEmulator newDevice = new PasskeyEmulator(IntegrationTest.BASE_URL);
    JdbcClient migrator;
    UUID aliceId;

    @BeforeEach
    void registerAliceOnHerOldDevice() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        for (var table : new String[] {"user_credentials", "user_entities", "invitation", "owner"}) {
            migrator.sql("DELETE FROM " + table).update();
        }
        var invitation = invitations.createInvitation(null, "Alice", true).url().getPath();
        var session = new MockHttpSession();
        var options = post(session, invitation + "/options", "{\"email\": \"" + ALICE + "\"}");
        assertThat(post(session, invitation + "/register", oldDevice.create(body(options)))).hasStatusOk();
        aliceId = jdbc.sql("SELECT id FROM owner WHERE email = ?").param(ALICE).query(UUID.class).single();
    }

    @Test
    void recoveryPageNamesTheOwnerAndAsksForNoEmail() {
        assertThat(mvc.get().uri(recoveryPath()))
                .hasStatusOk()
                .bodyText().contains("Nueva passkey", ALICE, "Crear passkey").doesNotContain("name=\"email\"");
    }

    @Test
    void theNewPasskeyIsAddedAndBothSignIn() {
        var recovery = recoveryPath();
        var session = new MockHttpSession();

        var options = post(session, recovery + "/options", "{\"email\": null}");
        assertThat(post(session, recovery + "/register", newDevice.create(body(options))))
                .hasStatusOk()
                .bodyJson().extractingPath("$.redirectUrl").isEqualTo("/");
        assertThat(mvc.get().uri("/").session(session)).hasStatusOk().bodyText().contains(ALICE);

        assertThat(jdbc.sql("SELECT count(*) FROM owner").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM user_entities").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM user_credentials").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT is_admin FROM owner").query(Boolean.class).single()).as("still an admin").isTrue();
        assertSignsInAsAlice(newDevice);
        assertSignsInAsAlice(oldDevice);
    }

    @Test
    void theAuthenticatorIsToldWhichPasskeysTheOwnerHas() {
        var oldCredentialId = jdbc.sql("SELECT credential_id FROM user_credentials").query(String.class).single();

        var options = body(post(new MockHttpSession(), recoveryPath() + "/options", "{}"));

        assertThat(options).contains("\"excludeCredentials\":[{", oldCredentialId);
    }

    @Test
    void anEmailSentWithTheRequestIsIgnored() {
        var recovery = recoveryPath();
        var session = new MockHttpSession();

        var options = post(session, recovery + "/options", "{\"email\": \"mallory@example.com\"}");
        post(session, recovery + "/register", newDevice.create(body(options)));

        assertThat(jdbc.sql("SELECT email FROM owner").query(String.class).list()).containsExactly(ALICE);
        assertThat(jdbc.sql("SELECT name FROM user_entities").query(String.class).list()).containsExactly(ALICE);
    }

    @Test
    void theLinkWorksOnce() {
        var recovery = recoveryPath();
        var session = new MockHttpSession();
        var options = post(session, recovery + "/options", "{}");
        post(session, recovery + "/register", newDevice.create(body(options)));

        assertThat(mvc.get().uri(recovery)).hasStatus(HttpStatus.NOT_FOUND).bodyText().contains("Enlace no válido");
        assertThat(post(new MockHttpSession(), recovery + "/options", "{}"))
                .hasStatus(HttpStatus.GONE)
                .bodyJson().extractingPath("$.error").isEqualTo("invitation");
    }

    @Test
    void deletingTheOwnerDeletesTheirRecoveryLinks() {
        var recovery = recoveryPath();

        migrator.sql("DELETE FROM owner").update();

        assertThat(mvc.get().uri(recovery)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aRecoveryLinkNeverGrantsAdmin() {
        assertThatThrownBy(() -> migrator.sql("""
                        INSERT INTO invitation (token_hash, recovers_owner_id, grants_admin, expires_at)
                        VALUES ('\\x01', ?, true, now() + interval '1 day')
                        """)
                .param(aliceId)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("invitation_recovery_grants_nothing_check");
    }

    private void assertSignsInAsAlice(PasskeyEmulator device) {
        var visit = new MockHttpSession();
        var requestOptions = mvc.post().uri("/webauthn/authenticate/options").session(visit).with(csrf()).exchange();
        assertThat(mvc.post().uri("/login/webauthn").session(visit).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(device.get(body(requestOptions))))
                .hasStatusOk()
                .bodyJson().extractingPath("$.authenticated").isEqualTo(true);
        assertThat(mvc.get().uri("/").session(visit)).hasStatusOk().bodyText().contains(ALICE);
    }

    private String recoveryPath() {
        return invitations.createPasskeyRecovery(null, aliceId).url().getPath();
    }

    private MvcTestResult post(MockHttpSession session, String path, String json) {
        return mvc.post().uri(path).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)
                .exchange();
    }

    private static String body(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
