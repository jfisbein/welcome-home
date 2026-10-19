package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.icegreen.greenmail.util.GreenMail;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.admin.InvitationService;
import welcomehome.notifications.MailStandInConfiguration;
import welcomehome.nuki.NukiStandInConfiguration;

/**
 * Alice registered on her phone: she hears of every passkey added to her account and of every recovery link
 * created for it, whatever her notification preferences. Same configuration as NotificationTests, so they share
 * one application context.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({NukiStandInConfiguration.class, MailStandInConfiguration.class})
@TestPropertySource(properties = "spring.mail.host=127.0.0.1")
class AccountSecurityEmailTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    InvitationService invitations;

    @Autowired
    GreenMail mail;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    final PasskeyEmulator phone = new PasskeyEmulator(IntegrationTest.BASE_URL);
    UUID aliceId;

    @BeforeEach
    void registerAliceOnHerPhone() throws Exception {
        var migrator = TestDatabaseConfiguration.asMigrator(postgres);
        for (var table : new String[] {"user_credentials", "user_entities", "invitation", "owner"}) {
            migrator.sql("DELETE FROM " + table).update();
        }
        mail.purgeEmailFromAllMailboxes();
        var invitation = invitations.createInvitation(null, ALICE, false).url().getPath();
        var session = new MockHttpSession();
        var options = body(mvc.post().uri(invitation + "/options").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"" + ALICE + "\"}").exchange());
        assertThat(mvc.post().uri(invitation + "/register").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(phone.create(options))).hasStatusOk();
        aliceId = jdbc.sql("SELECT id FROM owner WHERE email = ?").param(ALICE).query(UUID.class).single();
    }

    @Test
    void registeringOrSigningInIsNoNewsButAnAddedPasskeyIs() throws Exception {
        signIn(phone);
        assertNoMoreThan(0);

        var laptop = new PasskeyEmulator(IntegrationTest.BASE_URL);
        var session = new MockHttpSession();
        var credential = laptop.create(body(asAlice(session, "/webauthn/register/options", "")));
        assertThat(asAlice(session, "/webauthn/register", "{\"publicKey\": {\"credential\": " + credential
                + ", \"label\": \"Portátil\"}}")).hasStatusOk();

        var email = theOnlyEmail();
        assertThat(email.getSubject()).isEqualTo("Se ha añadido una passkey a tu cuenta de welcome-home");
        assertThat(email.getAllRecipients()).extracting(Object::toString).containsExactly(ALICE);
        assertThat(text(email)).contains("«Portátil»", IntegrationTest.BASE_URL + "/passkeys",
                "Este aviso se envía siempre");

        signIn(laptop);
        assertNoMoreThan(1);
    }

    @Test
    void aRecoveryLinkIsToldWhenCreatedAndWhenUsed() throws Exception {
        var recovery = invitations.createPasskeyRecovery(null, aliceId).url().getPath();

        var created = theOnlyEmail();
        assertThat(created.getSubject()).isEqualTo("Se ha creado un enlace para recuperar tu cuenta de welcome-home");
        assertThat(text(created)).contains("caduca cuando se usa o a las 72 horas", "avisa a un administrador");

        var session = new MockHttpSession();
        var options = body(mvc.post().uri(recovery + "/options").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\": null}").exchange());
        assertThat(mvc.post().uri(recovery + "/register").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(new PasskeyEmulator(IntegrationTest.BASE_URL).create(options))).hasStatusOk();

        await().atMost(Duration.ofSeconds(5)).until(() -> mail.getReceivedMessages().length == 2);
        assertThat(mail.getReceivedMessages()[1].getSubject())
                .isEqualTo("Se ha añadido una passkey a tu cuenta de welcome-home");
    }

    @Test
    void theyAreSentWhateverTheOwnersPreferences() throws Exception {
        assertThat(mvc.put().uri("/notifications").with(user(ALICE).roles("OWNER")).with(csrf()))
                .hasStatus3xxRedirection();

        invitations.createPasskeyRecovery(null, aliceId);

        assertThat(theOnlyEmail().getSubject())
                .isEqualTo("Se ha creado un enlace para recuperar tu cuenta de welcome-home");
    }

    private void signIn(PasskeyEmulator device) {
        var visit = new MockHttpSession();
        var requestOptions = body(mvc.post().uri("/webauthn/authenticate/options").session(visit).with(csrf())
                .exchange());
        assertThat(mvc.post().uri("/login/webauthn").session(visit).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(device.get(requestOptions))).hasStatusOk();
    }

    private MvcTestResult asAlice(MockHttpSession session, String path, String json) {
        var request = mvc.post().uri(path).session(session).with(user(ALICE).roles("OWNER")).with(csrf());
        return json.isEmpty() ? request.exchange()
                : request.contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private MimeMessage theOnlyEmail() {
        await().atMost(Duration.ofSeconds(5)).until(() -> mail.getReceivedMessages().length >= 1);
        assertNoMoreThan(1);
        return mail.getReceivedMessages()[0];
    }

    /** Waits a little for emails still on their way, which there should not be. */
    private void assertNoMoreThan(int count) {
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> mail.getReceivedMessages().length <= count);
        assertThat(mail.getReceivedMessages()).hasSize(count);
    }

    private static String body(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String text(MimeMessage email) {
        try {
            return (String) email.getContent();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
