package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

@IntegrationTest
@AutoConfigureMockMvc
class InvitationRegistrationTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    InvitationService invitations;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    final MockHttpSession session = new MockHttpSession();
    final PasskeyEmulator passkeys = new PasskeyEmulator(IntegrationTest.BASE_URL);

    @BeforeEach
    void emptyDatabase() {
        var migrator = TestDatabaseConfiguration.asMigrator(postgres);
        for (var table : new String[] {"user_credentials", "user_entities", "invitation", "owner"}) {
            migrator.sql("DELETE FROM " + table).update();
        }
    }

    @Test
    void invitationPageAsksForEmailAndPasskey() {
        assertThat(mvc.get().uri(invitationPath(true)))
                .hasStatusOk()
                .bodyText().contains("Crear passkey");
    }

    @Test
    void unknownInvitationShowsItIsNotValid() {
        assertThat(mvc.get().uri("/invite/unknown"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyText().contains("Enlace no válido");
    }

    @Test
    void newOwnerRegistersAPasskeyAndCanSignInWithIt() {
        var invitation = invitationPath(true);

        var passkey = passkeys.create(creationOptions(invitation, "Alice@Example.com"));
        assertThat(post(invitation + "/register", passkey))
                .hasStatusOk()
                .bodyJson().extractingPath("$.redirectUrl").isEqualTo("/");
        assertThat(mvc.get().uri("/").session(session)).hasStatusOk().bodyText().contains("alice@example.com");

        assertThat(jdbc.sql("SELECT is_admin FROM owner WHERE email = 'alice@example.com'").query(Boolean.class).single())
                .isTrue();
        assertThat(jdbc.sql("SELECT used_at IS NOT NULL FROM invitation").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM user_credentials").query(Long.class).single()).isEqualTo(1);

        var newVisit = new MockHttpSession();
        var requestOptions = mvc.post().uri("/webauthn/authenticate/options").session(newVisit).with(csrf()).exchange();
        var assertion = passkeys.get(body(requestOptions));
        assertThat(mvc.post().uri("/login/webauthn").session(newVisit).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(assertion))
                .hasStatusOk()
                .bodyJson().extractingPath("$.authenticated").isEqualTo(true);
        assertThat(mvc.get().uri("/").session(newVisit)).hasStatusOk().bodyText().contains("alice@example.com");
    }

    @Test
    void ownerInvitationDoesNotGrantAdmin() {
        var invitation = invitationPath(false);

        post(invitation + "/register", passkeys.create(creationOptions(invitation, "bob@example.com")));

        assertThat(jdbc.sql("SELECT is_admin FROM owner WHERE email = 'bob@example.com'").query(Boolean.class).single())
                .isFalse();
    }

    @Test
    void invalidEmailIsRejected() {
        assertThat(post(invitationPath(false) + "/options", "{\"email\": \"not-an-email\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.error").isEqualTo("email");
    }

    @Test
    void emailOfAnExistingOwnerIsRejected() {
        TestDatabaseConfiguration.asMigrator(postgres).sql("INSERT INTO owner (email) VALUES ('alice@example.com')").update();

        assertThat(post(invitationPath(false) + "/options", "{\"email\": \"alice@example.com\"}"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.error").isEqualTo("email-taken");
    }

    @Test
    void ofTwoSimultaneousRegistrationsWithOneEmailTheSecondIsToldItIsTaken() throws Exception {
        var invitation = invitationPath(false);
        var passkey = passkeys.create(creationOptions(invitation, "alice@example.com"));

        // The other registration, from another invitation, has inserted its owner but not committed yet: this
        // one's check cannot see it, and its insert waits for the other's transaction.
        try (var other = TestDatabaseConfiguration.newSessionsAsAppUser(postgres).getConnection()) {
            other.setAutoCommit(false);
            try (var insert = other.prepareStatement("INSERT INTO owner (email) VALUES ('alice@example.com')")) {
                insert.executeUpdate();
            }
            var registering = CompletableFuture.supplyAsync(() -> post(invitation + "/register", passkey));
            var migrator = TestDatabaseConfiguration.asMigrator(postgres);
            await(() -> migrator.sql("SELECT count(*) FROM pg_locks WHERE NOT granted").query(Long.class).single() > 0);
            other.commit();

            assertThat(registering.get(10, TimeUnit.SECONDS))
                    .hasStatus(HttpStatus.CONFLICT)
                    .bodyJson().extractingPath("$.error").isEqualTo("email-taken");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM owner").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM user_entities").query(Long.class).single()).isZero();
        assertThat(mvc.get().uri(invitation)).as("the invitation stays usable").hasStatusOk();
    }

    @Test
    void unknownInvitationCannotStartARegistration() {
        assertThat(post("/invite/unknown/options", "{\"email\": \"alice@example.com\"}"))
                .hasStatus(HttpStatus.GONE)
                .bodyJson().extractingPath("$.error").isEqualTo("invitation");
    }

    @Test
    void passkeyForAnotherSiteIsRejectedAndTheInvitationStaysUsable() {
        var invitation = invitationPath(false);
        var phishingSite = new PasskeyEmulator("https://welcome-example.evil");

        var passkey = phishingSite.create(creationOptions(invitation, "alice@example.com"));
        assertThat(post(invitation + "/register", passkey))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.error").isEqualTo("passkey");

        assertThat(jdbc.sql("SELECT count(*) FROM owner").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM user_entities").query(Long.class).single()).isZero();
        assertThat(mvc.get().uri(invitation)).hasStatusOk();
    }

    @Test
    void registrationWithoutOptionsIsRejected() {
        assertThat(post(invitationPath(false) + "/register", "{}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.error").isEqualTo("expired");
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("waited 10 s").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private String invitationPath(boolean grantsAdmin) {
        return invitations.createInvitation(null, "Test", grantsAdmin).url().getPath();
    }

    private String creationOptions(String invitation, String email) {
        var result = post(invitation + "/options", "{\"email\": \"" + email + "\"}");
        assertThat(result).hasStatusOk();
        return body(result);
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private MvcTestResult post(String path, String json) {
        return mvc.post().uri(path).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)
                .exchange();
    }
}
