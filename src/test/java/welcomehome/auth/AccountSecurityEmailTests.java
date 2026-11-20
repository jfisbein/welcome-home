package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import welcomehome.TestData;
import welcomehome.SignedIn;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
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
        new TestData(migrator).deleteOwners();
        mail.purgeEmailFromAllMailboxes();
        var invitation = invitations.createInvitation(null, ALICE, false).url().getPath();
        var session = new MockHttpSession();
        var options = body(mvc.post().uri(invitation + "/options").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"" + ALICE + "\"}").exchange());
        assertThat(mvc.post().uri(invitation + "/register").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(phone.create(options))).hasStatusOk();
        aliceId = jdbc.sql("SELECT id FROM owner WHERE email = ?").param(ALICE).query(UUID.class).single();
        // A new owner's first email is the link that verifies their address; these tests start after it.
        await().atMost(Duration.ofSeconds(5)).until(() -> mail.getReceivedMessages().length == 1);
        new TestData(TestDatabaseConfiguration.asMigrator(postgres)).verifyEmail(aliceId);
        mail.purgeEmailFromAllMailboxes();
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
        assertThat(text(email)).contains("«Portátil»", IntegrationTest.BASE_URL + "/settings/passkeys",
                "Este aviso se envía siempre");

        signIn(laptop);
        assertNoMoreThan(1);
    }

    @Test
    void aRecoveryLinkIsToldWhenCreatedAndWhenUsed() throws Exception {
        var created4h = invitations.createPasskeyRecovery(null, aliceId);
        assertThat(created4h.expiresAt()).as("a few hours, not an invitation's three days")
                .isBetween(Instant.now().plus(Duration.ofMinutes(239)), Instant.now().plus(Duration.ofMinutes(241)));
        assertThat(invitations.createInvitation(null, "Bob", false).expiresAt())
                .isBetween(Instant.now().plus(Duration.ofMinutes(72 * 60 - 1)), Instant.now().plus(Duration.ofMinutes(72 * 60 + 1)));
        var recovery = created4h.url().getPath();

        var created = theOnlyEmail();
        assertThat(created.getSubject()).isEqualTo("Se ha creado un enlace para recuperar tu cuenta de welcome-home");
        assertThat(text(created)).contains("caduca cuando se usa o a las 4 horas", "avisa a un administrador");

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
    void theyAreWrittenInTheOwnersLanguage() throws Exception {
        new TestData(TestDatabaseConfiguration.asMigrator(postgres)).language("en");

        invitations.createPasskeyRecovery(null, aliceId);

        var email = theOnlyEmail();
        assertThat(email.getSubject()).isEqualTo("A link to recover your welcome-home account was created");
        assertThat(text(email)).contains("after 4 hours", "This notification is always sent");
    }

    @Test
    void theyAreSentWhateverTheOwnersPreferences() throws Exception {
        assertThat(mvc.put().uri("/settings/notifications").with(SignedIn.owner(ALICE)).with(csrf()))
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

    /** As Alice, in a session that signed in just now, which adding a passkey asks for. */
    private MvcTestResult asAlice(MockHttpSession session, String path, String json) {
        session.setAttribute(RecentSignIn.ATTRIBUTE, Instant.now());
        var request = mvc.post().uri(path).session(session).with(SignedIn.owner(ALICE)).with(csrf());
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
