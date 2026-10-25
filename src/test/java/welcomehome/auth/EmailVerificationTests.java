package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

import com.icegreen.greenmail.util.GreenMail;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.admin.InvitationService;
import welcomehome.notifications.MailStandInConfiguration;
import welcomehome.nuki.NukiStandInConfiguration;

/**
 * Alice registers: until she presses the button behind the link sent to her email, nothing else goes there.
 * Same configuration as NotificationTests, so they share one application context.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({NukiStandInConfiguration.class, MailStandInConfiguration.class})
@TestPropertySource(properties = "spring.mail.host=127.0.0.1")
class EmailVerificationTests {

    static final String ALICE = "alice@example.com";
    static final Pattern LINK = Pattern.compile(Pattern.quote(IntegrationTest.BASE_URL) + "(/email/verify/[A-Za-z0-9_-]+)");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    InvitationService invitations;

    @Autowired
    EmailVerification emailVerification;

    @Autowired
    GreenMail mail;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID aliceId;

    @BeforeEach
    void aliceRegisters() throws Exception {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        for (var table : new String[] {"user_credentials", "user_entities", "invitation", "owner"}) {
            migrator.sql("DELETE FROM " + table).update();
        }
        mail.purgeEmailFromAllMailboxes();
        var invitation = invitations.createInvitation(null, ALICE, false).url().getPath();
        var session = new MockHttpSession();
        var options = body(mvc.post().uri(invitation + "/options").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"" + ALICE + "\"}").exchange());
        assertThat(mvc.post().uri(invitation + "/register").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(new PasskeyEmulator(IntegrationTest.BASE_URL).create(options))).hasStatusOk();
        aliceId = migrator.sql("SELECT id FROM owner WHERE email = ?").param(ALICE).query(UUID.class).single();
    }

    @Test
    void aNewOwnerGetsALinkAndNothingElseUntilTheyPressItsButton() throws Exception {
        var email = theOnlyEmail();
        assertThat(email.getSubject()).isEqualTo("Confirma tu email en welcome-home");
        assertThat(email.getAllRecipients()).extracting(Object::toString).containsExactly(ALICE);
        assertThat(text(email)).contains("caduca en 7 días");
        var link = linkIn(email);

        invitations.createPasskeyRecovery(null, aliceId);
        assertNoMoreThan(1);

        assertThat(mvc.get().uri(link)).hasStatusOk().bodyText()
                .contains("Confirma que <strong>alice@example.com</strong> es tu email", "Confirmar");
        assertThat(emailVerification.isVerified(aliceId)).as("opening the link verifies nothing").isFalse();

        assertThat(mvc.post().uri(link).with(csrf())).hasStatusOk().bodyText()
                .contains("Email confirmado: los avisos llegarán a <strong>alice@example.com</strong>.");
        assertThat(emailVerification.isVerified(aliceId)).isTrue();
        assertThat(mvc.post().uri(link).with(csrf())).as("the link works once").hasStatus(HttpStatus.NOT_FOUND)
                .bodyText().contains("Este enlace ya se ha usado o ha caducado");

        invitations.createPasskeyRecovery(null, aliceId);
        await().atMost(Duration.ofSeconds(5)).until(() -> mail.getReceivedMessages().length == 2);
        assertThat(mail.getReceivedMessages()[1].getSubject())
                .isEqualTo("Se ha creado un enlace para recuperar tu cuenta de welcome-home");
    }

    @Test
    void theLinkExpires() throws Exception {
        var link = linkIn(theOnlyEmail());
        migrator.sql("UPDATE owner SET email_verification_expires_at = now() - interval '1 second'").update();

        assertThat(mvc.get().uri(link)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.post().uri(link).with(csrf())).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(emailVerification.isVerified(aliceId)).isFalse();
    }

    @Test
    void aNewLinkReplacesTheOldOneButNotTooOften() throws Exception {
        var first = linkIn(theOnlyEmail());

        assertThat(mvc.post().uri("/email/verification").with(owner()).with(csrf())).hasStatus3xxRedirection()
                .flash().containsKey("verificationRecent");
        assertNoMoreThan(1);

        // As if the link had gone out six minutes ago. In hours: '7 days' is 169 of them across a DST change.
        migrator.sql("UPDATE owner SET email_verification_expires_at = now() + interval '168 hours' - interval '6 minutes'")
                .update();
        assertThat(mvc.post().uri("/email/verification").with(owner()).with(csrf())).hasStatus3xxRedirection()
                .flash().containsKey("verificationSent");
        await().atMost(Duration.ofSeconds(5)).until(() -> mail.getReceivedMessages().length == 2);
        var second = linkIn(mail.getReceivedMessages()[1]);

        assertThat(second).isNotEqualTo(first);
        assertThat(mvc.get().uri(first)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri(second)).hasStatusOk();
    }

    @Test
    void thePanelAndTheNotificationsPageSaySoUntilThen() {
        assertThat(mvc.get().uri("/").with(owner())).hasStatusOk().bodyText()
                .contains("Confirma tu email.", "Enviar el enlace de nuevo");
        assertThat(mvc.get().uri("/settings/notifications").with(owner())).hasStatusOk().bodyText()
                .contains("Tu email no está confirmado").doesNotContain("/settings/notifications/test");

        migrator.sql("UPDATE owner SET email_verified_at = now()").update();

        assertThat(mvc.get().uri("/").with(owner())).bodyText().doesNotContain("Confirma tu email.");
        assertThat(mvc.get().uri("/settings/notifications").with(owner())).bodyText()
                .doesNotContain("Tu email no está confirmado").contains("/settings/notifications/test");
    }

    @Test
    void ownersRegisteredBeforeVerificationExistedAreVerified() {
        var bob = migrator.sql("INSERT INTO owner (email) VALUES ('bob@example.com') RETURNING id")
                .query(UUID.class).single();

        assertThat(emailVerification.isVerified(bob)).isTrue();
    }

    private String linkIn(MimeMessage email) {
        var matcher = LINK.matcher(text(email));
        assertThat(matcher.find()).as("a verification link in the email").isTrue();
        return matcher.group(1);
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

    private static RequestPostProcessor owner() {
        return user(ALICE).roles("OWNER");
    }
}
