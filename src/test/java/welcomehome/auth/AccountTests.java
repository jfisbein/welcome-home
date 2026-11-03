package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.icegreen.greenmail.util.GreenMail;
import jakarta.mail.MessagingException;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.notifications.MailStandInConfiguration;
import welcomehome.nuki.NukiStandInConfiguration;

/**
 * Alice registered on her phone and verified her email: she changes it, or closes her account. Same configuration
 * as NotificationTests, so they share one application context.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({NukiStandInConfiguration.class, MailStandInConfiguration.class})
@TestPropertySource(properties = "spring.mail.host=127.0.0.1")
class AccountTests {

    static final String ALICE = "alice@example.com";
    static final String NEW_EMAIL = "ana.nueva@example.com";
    static final String BOB = "bob@example.com";
    static final List<String> EMAILS_ABOUT_THE_CHANGE = List.of(
            NEW_EMAIL + ": Confirma tu email en welcome-home",
            ALICE + ": Ha cambiado el email de tu cuenta de welcome-home");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    InvitationService invitations;

    @Autowired
    GreenMail mail;

    @Autowired
    PostgreSQLContainer postgres;

    final PasskeyEmulator phone = new PasskeyEmulator(IntegrationTest.BASE_URL);
    JdbcClient migrator;
    TestData data;
    UUID aliceId;

    @BeforeEach
    void registerAliceOnHerPhone() throws Exception {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
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
        aliceId = migrator.sql("SELECT id FROM owner WHERE email = ?").param(ALICE).query(UUID.class).single();
        // A new owner's first email is the link that verifies their address; these tests start after it.
        await().atMost(Duration.ofSeconds(5)).until(() -> mail.getReceivedMessages().length == 1);
        migrator.sql("UPDATE owner SET email_verified_at = now() WHERE id = ?").param(aliceId).update();
        mail.purgeEmailFromAllMailboxes();
    }

    @Test
    void thePageShowsTheEmailAndBothActions() {
        assertThat(mvc.get().uri("/settings/account").session(signIn())).hasStatusOk().bodyText()
                .contains("<h1>Cuenta</h1>", "name=\"email\"", "value=\"" + ALICE + "\"", "Cambiar email",
                        "data-confirm=\"Se borrarán para siempre tu cuenta y todo lo tuyo", "Darme de baja",
                        "href=\"/settings/account\" aria-current=\"page\"")
                .doesNotContain("Confirma tu email");
    }

    @Test
    void changingTheEmailMovesTheAccountAndItsPasskeysToTheNewOne() throws MessagingException {
        var session = signIn();

        var result = changeEmail(session, "  Ana.Nueva@Example.com ");

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/settings/account");
        assertThat(owner()).isEqualTo(NEW_EMAIL + "|unverified");
        assertThat(passkeyUsers()).isEqualTo(List.of(NEW_EMAIL + "|" + NEW_EMAIL + "|" + aliceId));
        assertThat(mvc.get().uri("/settings/account").session(session).flashAttr("emailChanged", NEW_EMAIL))
                .as("the session goes on under the new email").hasStatusOk().bodyText()
                .contains("Email cambiado a " + NEW_EMAIL, "value=\"" + NEW_EMAIL + "\"", "Confirma tu email");
        assertThat(mvc.get().uri("/settings/account").session(signIn())).as("the passkey signs in as the new email")
                .hasStatusOk().bodyText().contains("value=\"" + NEW_EMAIL + "\"");
        assertThat(emails(2)).containsExactlyInAnyOrderElementsOf(EMAILS_ABOUT_THE_CHANGE);
    }

    /** The new address gets the link that verifies it; the previous one, which can tell whether it was Alice, a notice. */
    @Test
    void theNewAddressIsVerifiedAndThePreviousOneIsTold() throws MessagingException {
        changeEmail(signIn(), NEW_EMAIL);

        assertThat(emails(2)).containsExactlyInAnyOrderElementsOf(EMAILS_ABOUT_THE_CHANGE);
        var notice = Arrays.stream(mail.getReceivedMessages()).filter(email -> recipient(email).equals(ALICE))
                .findFirst().orElseThrow();
        assertThat(text(notice)).contains("ha cambiado de esta dirección a " + NEW_EMAIL,
                "avisa a quien administra welcome-home", "Este aviso se envía siempre");
    }

    @Test
    void aPreviousAddressNeverVerifiedIsNotWrittenTo() throws MessagingException {
        migrator.sql("UPDATE owner SET email_verified_at = NULL WHERE id = ?").param(aliceId).update();

        changeEmail(signIn(), NEW_EMAIL);

        assertThat(emails(1)).isEqualTo(List.of(NEW_EMAIL + ": Confirma tu email en welcome-home"));
    }

    @Test
    void theOwnersOtherSessionsEndAsTheyKnowThemByThePreviousEmail() throws MessagingException {
        var here = signIn();
        var elsewhere = signIn();
        for (var session : List.of(here, elsewhere)) {
            assertThat(mvc.get().uri("/settings/account").session(session)).hasStatusOk();
        }

        changeEmail(here, NEW_EMAIL);

        assertThat(elsewhere.isInvalid()).isTrue();
        assertThat(mvc.get().uri("/settings/account").session(here)).hasStatusOk();
        assertThat(emails(2)).containsExactlyInAnyOrderElementsOf(EMAILS_ABOUT_THE_CHANGE);
    }

    @Test
    void anInvalidEmailIsRefusedAndNothingChanges() {
        var session = signIn();
        for (var email : new String[] {"", "no es un email", "x".repeat(90) + "@example.com"}) {
            assertThat(changeEmail(session, email)).as(email).hasStatus(HttpStatus.BAD_REQUEST).bodyText()
                    .contains("Escribe un email válido, de hasta 100 caracteres.");
        }

        assertThat(owner()).isEqualTo(ALICE + "|verified");
    }

    @Test
    void anotherOwnersEmailIsRefusedAndNothingChanges() throws MessagingException {
        data.owner(BOB);

        assertThat(changeEmail(signIn(), "Bob@Example.com")).hasStatus(HttpStatus.BAD_REQUEST).bodyText()
                .contains("Ya hay una cuenta con ese email.", "value=\"Bob@Example.com\"");

        assertThat(owner()).isEqualTo(ALICE + "|verified");
        assertThat(emails(0)).isEqualTo(List.of());
    }

    @Test
    void theSameEmailChangesNothing() throws MessagingException {
        assertThat(changeEmail(signIn(), "Alice@Example.com")).hasStatus(HttpStatus.FOUND);

        assertThat(owner()).isEqualTo(ALICE + "|verified");
        assertThat(emails(0)).isEqualTo(List.of());
    }

    @Test
    void anOwnerClosesTheirAccountAndIsSignedOut() {
        var session = signIn();

        assertThat(mvc.delete().uri("/settings/account").session(session).with(csrf()))
                .hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/login?deleted");

        assertThat(migrator.sql("SELECT email FROM owner").query(String.class).list()).isEqualTo(List.of());
        assertThat(session.isInvalid()).isTrue();
        assertThat(mvc.get().uri("/login?deleted")).bodyText()
                .contains("Tu cuenta se ha dado de baja, con todos sus datos.");
        var options = body(mvc.post().uri("/webauthn/authenticate/options").with(csrf()).exchange());
        assertThat(mvc.post().uri("/login/webauthn").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(phone.get(options))).as("her passkey no longer signs in").hasStatus(HttpStatus.UNAUTHORIZED);
    }

    /** So that an admin is always left, as the admin panel lets no admin delete themselves either. */
    @Test
    void theOnlyAdminCannotCloseTheirAccount() {
        migrator.sql("UPDATE owner SET is_admin = true WHERE id = ?").param(aliceId).update();
        var session = signIn();

        assertThat(mvc.get().uri("/settings/account").session(session)).bodyText()
                .contains("Eres el único administrador").doesNotContain("Darme de baja");
        assertThat(mvc.delete().uri("/settings/account").session(session).with(csrf()))
                .hasStatus(HttpStatus.CONFLICT);
        assertThat(migrator.sql("SELECT email FROM owner").query(String.class).list()).isEqualTo(List.of(ALICE));

        data.admin(BOB);
        assertThat(mvc.get().uri("/settings/account").session(session)).bodyText()
                .contains("Darme de baja").doesNotContain("Eres el único administrador");
    }

    @Test
    void changesRequireTheCsrfToken() {
        var session = signIn();

        assertThat(mvc.put().uri("/settings/account/email").session(session).param("email", NEW_EMAIL))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.delete().uri("/settings/account").session(session)).hasStatus(HttpStatus.FORBIDDEN);

        assertThat(owner()).isEqualTo(ALICE + "|verified");
    }

    private MvcTestResult changeEmail(MockHttpSession session, String email) {
        return mvc.put().uri("/settings/account/email").session(session).with(csrf()).param("email", email).exchange();
    }

    private MockHttpSession signIn() {
        var visit = new MockHttpSession();
        var options = body(mvc.post().uri("/webauthn/authenticate/options").session(visit).with(csrf()).exchange());
        assertThat(mvc.post().uri("/login/webauthn").session(visit).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(phone.get(options))).hasStatusOk();
        return visit;
    }

    /** Alice's email, and whether it is verified. */
    private String owner() {
        return migrator.sql("""
                        SELECT email || '|' || CASE WHEN email_verified_at IS NULL THEN 'unverified' ELSE 'verified' END
                        FROM owner WHERE id = ?
                        """)
                .param(aliceId).query(String.class).single();
    }

    private List<String> passkeyUsers() {
        return migrator.sql("SELECT name || '|' || display_name || '|' || owner_id FROM user_entities")
                .query(String.class).list();
    }

    /** The emails sent, as "recipient: subject", once there are as many as expected and no more. */
    private List<String> emails(int expected) throws MessagingException {
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                .until(() -> mail.getReceivedMessages().length == expected);
        var sent = new ArrayList<String>();
        for (var email : mail.getReceivedMessages()) {
            sent.add(recipient(email) + ": " + email.getSubject());
        }
        return sent;
    }

    private static String recipient(MimeMessage email) {
        try {
            return email.getAllRecipients()[0].toString();
        } catch (MessagingException e) {
            throw new IllegalStateException(e);
        }
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
