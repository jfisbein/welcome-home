package welcomehome.notifications;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.icegreen.greenmail.util.GreenMail;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.audit.AccessEvent;
import welcomehome.audit.AccessEvents;
import welcomehome.audit.AccessOutcome;
import welcomehome.crypto.CryptoService;
import welcomehome.nuki.LockProvider;
import welcomehome.nuki.LockProviderException;
import welcomehome.nuki.NukiStandInConfiguration;

/** Alice's lock "Casa", in New York, and her guest Ana's link: what Alice hears about by email. */
@IntegrationTest
@AutoConfigureMockMvc
@Import({NukiStandInConfiguration.class, MailStandInConfiguration.class})
@TestPropertySource(properties = "spring.mail.host=127.0.0.1")
class NotificationTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    GreenMail mail;

    @Autowired
    WireMockServer nuki;

    @Autowired
    AccessEvents accessEvents;

    @Autowired
    LockProvider lockProvider;

    @Autowired
    CryptoService crypto;

    @Autowired
    JavaMailSenderImpl mailSender;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID aliceId;
    UUID lockId;
    UUID linkId;

    @BeforeEach
    void insertAlicesLockAndLink() throws Exception {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        aliceId = migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id").param(ALICE).query(UUID.class).single();
        lockId = migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, 17, 'Casa', 'America/New_York') RETURNING id
                        """)
                .param(aliceId).query(UUID.class).single();
        linkId = insertLink("Ana");
        nuki.resetAll();
        mail.purgeEmailFromAllMailboxes();
    }

    @Test
    void anOpeningIsToldInTheLocksTimeZoneWithALinkToItsHistory() throws Exception {
        record(linkId, AccessOutcome.OPENED);

        var email = theOnlyEmail();
        assertThat(email.getSubject()).isEqualTo("Casa: Ana ha abierto la puerta");
        assertThat(email.getAllRecipients()).extracting(Object::toString).containsExactly(ALICE);
        assertThat(email.getFrom()).extracting(Object::toString).containsExactly(MailStandInConfiguration.FROM);
        assertThat(text(email)).contains("Ana ha abierto la puerta de Casa el ", "(hora de America/New_York)",
                IntegrationTest.BASE_URL + "/locks/" + lockId + "/history?link=" + linkId,
                IntegrationTest.BASE_URL + "/settings/notifications");
    }

    @Test
    void aFailureSaysWhyTheGuestCouldNotGetIn() throws Exception {
        record(linkId, AccessOutcome.NIGHT_MODE);

        var email = theOnlyEmail();
        assertThat(email.getSubject()).isEqualTo("Casa: Ana no ha podido abrir la puerta");
        assertThat(text(email)).contains("la cerradura está en modo nocturno");
    }

    @Test
    void anUnavailableDoorIsAFailure() throws Exception {
        record(linkId, AccessOutcome.UNAVAILABLE);

        var email = theOnlyEmail();
        assertThat(email.getSubject()).isEqualTo("Casa: Ana no ha podido abrir la puerta");
        assertThat(text(email)).contains("la cerradura no está disponible");
    }

    @Test
    void anUnconfirmedOpeningSaysTheDoorMayHaveOpened() throws Exception {
        record(linkId, AccessOutcome.NOT_CONFIRMED);

        var email = theOnlyEmail();
        assertThat(email.getSubject()).isEqualTo("Casa: la cerradura no ha confirmado que Ana abriera la puerta");
        assertThat(text(email)).contains("Ana ha pedido abrir la puerta de Casa el ",
                "no ha confirmado que se abriera: puede que se abriera o que no.");
    }

    @Test
    void everyOutcomeHasItsEmail() throws Exception {
        for (var outcome : AccessOutcome.values()) {
            record(insertLink(outcome.code()), outcome);
        }

        await().atMost(Duration.ofSeconds(5))
                .until(() -> mail.getReceivedMessages().length == AccessOutcome.values().length);
        for (var message : mail.getReceivedMessages()) {
            assertThat(message.getSubject() + "\n" + text(message)).doesNotContain("{", "}", "null", "notification.");
        }
    }

    @Test
    void anOwnerIsWrittenToInTheirLanguage() throws Exception {
        migrator.sql("UPDATE owner SET language = 'en'").update();

        record(linkId, AccessOutcome.OPENED);

        var email = theOnlyEmail();
        assertThat(email.getSubject()).isEqualTo("Casa: Ana opened the door");
        assertThat(text(email)).containsPattern("Ana opened the door of Casa on \\d{2}/\\d{2}/\\d{4} at \\d{2}:\\d{2}")
                .contains("(America/New_York time)", "Choose which notifications you get");

        assertThat(mvc.post().uri("/settings/notifications/test").with(owner()).with(csrf())).hasStatus3xxRedirection();
        await().atMost(Duration.ofSeconds(5)).until(() -> mail.getReceivedMessages().length == 2);
        assertThat(mail.getReceivedMessages()[1].getSubject()).isEqualTo("welcome-home test email");
    }

    @Test
    void refusedAttemptsWithALinkAreToldOnceInAWhile() throws Exception {
        record(linkId, AccessOutcome.EXPIRED);
        assertThat(text(theOnlyEmail())).contains("el enlace ha caducado", "regenéralo o revócalo");

        // Emails are sent on other threads: one attempt at a time, so that the first one is the one told.
        record(linkId, AccessOutcome.TOO_MANY_ATTEMPTS);
        record(insertLink("Bea"), AccessOutcome.REVOKED);

        await().atMost(Duration.ofSeconds(5)).until(() -> mail.getReceivedMessages().length >= 2);
        assertNoMoreThan(2);
        assertThat(subjects()).containsExactlyInAnyOrder(
                "Casa: intento denegado con el enlace de Ana", "Casa: intento denegado con el enlace de Bea");
    }

    @Test
    void nukiRejectingTheTokenIsToldOnce() throws Exception {
        migrator.sql("INSERT INTO nuki_connection (owner_id, api_token_encrypted, status) VALUES (?, ?, 'OK')")
                .params(aliceId, crypto.encryptApiToken(aliceId, "rejected-token")).update();
        nuki.stubFor(get("/smartlock").willReturn(status(401)));

        assertThatThrownBy(() -> lockProvider.listLocks(aliceId)).isInstanceOf(LockProviderException.class);

        var email = theOnlyEmail();
        assertThat(email.getSubject()).isEqualTo("Nuki ha rechazado tu API token");
        assertThat(text(email)).contains(IntegrationTest.BASE_URL + "/settings/nuki");

        assertThatThrownBy(() -> lockProvider.listLocks(aliceId)).as("already disconnected")
                .isInstanceOf(LockProviderException.class);
        assertNoMoreThan(1);
    }

    @Test
    void twoCallsRejectedAtOnceSendOneEmail() throws Exception {
        migrator.sql("INSERT INTO nuki_connection (owner_id, api_token_encrypted, status) VALUES (?, ?, 'OK')")
                .params(aliceId, crypto.encryptApiToken(aliceId, "rejected-token")).update();
        // Slow enough for both calls to find the connection OK before either is rejected.
        nuki.stubFor(get("/smartlock").willReturn(status(401).withFixedDelay(500)));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var calls = List.of(executor.submit(() -> lockProvider.listLocks(aliceId)),
                    executor.submit(() -> lockProvider.listLocks(aliceId)));
            for (var call : calls) {
                assertThatThrownBy(call::get).hasCauseInstanceOf(LockProviderException.class);
            }
        }

        assertThat(nuki.getAllServeEvents()).as("both calls reached Nuki").hasSize(2);
        assertThat(theOnlyEmail().getSubject()).isEqualTo("Nuki ha rechazado tu API token");
    }

    @Test
    void anOwnerGetsOnlyTheEmailsTheyChose() throws Exception {
        assertThat(mvc.get().uri("/settings/notifications").with(owner())).hasStatusOk().bodyText()
                .contains("name=\"opened\" value=\"true\" checked", "name=\"denied\" value=\"true\" checked")
                .doesNotContain("no está configurado");

        assertThat(mvc.put().uri("/settings/notifications").with(owner()).with(csrf())
                .param("failed", "true").param("disconnected", "true"))
                .hasStatus3xxRedirection();

        assertThat(mvc.get().uri("/settings/notifications").with(owner())).bodyText()
                .contains("name=\"failed\" value=\"true\" checked")
                .doesNotContain("name=\"opened\" value=\"true\" checked", "name=\"denied\" value=\"true\" checked");
        record(linkId, AccessOutcome.OPENED);
        record(insertLink("Bea"), AccessOutcome.EXPIRED);
        assertNoMoreThan(0);

        record(linkId, AccessOutcome.LOCK_FAILED);
        assertThat(theOnlyEmail().getSubject()).isEqualTo("Casa: Ana no ha podido abrir la puerta");
    }

    @Test
    void theOwnerCanSendThemselvesATestEmail() throws Exception {
        assertThat(mvc.post().uri("/settings/notifications/test").with(owner()).with(csrf())).hasStatus3xxRedirection();

        var email = theOnlyEmail();
        assertThat(email.getSubject()).isEqualTo("Email de prueba de welcome-home");
        assertThat(email.getAllRecipients()).extracting(Object::toString).containsExactly(ALICE);
    }

    @Test
    void changingPreferencesRequiresTheCsrfToken() throws Exception {
        assertThat(mvc.put().uri("/settings/notifications").with(owner())).hasStatus4xxClientError();
        assertThat(mvc.post().uri("/settings/notifications/test").with(owner())).hasStatus4xxClientError();
    }

    @Test
    void productionRequiresStartTlsAndTimesOut() throws Exception {
        // The stand-in turns STARTTLS off; these come from application.yaml.
        assertThat(mailSender.getJavaMailProperties())
                .containsKeys("mail.smtp.starttls.required", "mail.smtp.connectiontimeout", "mail.smtp.timeout");
    }

    private void record(UUID link, AccessOutcome outcome) {
        accessEvents.record(new AccessEvent(aliceId, link, lockId, outcome, "203.0.113.7", "ES", "curl"));
    }

    private UUID insertLink(String label) {
        return migrator.sql("""
                        INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until)
                        VALUES (?, ?, ?, now() - interval '1 day', now() + interval '1 day') RETURNING id
                        """)
                .params(aliceId, lockId, label).query(UUID.class).single();
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

    private List<String> subjects() throws Exception {
        var subjects = new ArrayList<String>();
        for (var message : mail.getReceivedMessages()) {
            subjects.add(message.getSubject());
        }
        return subjects;
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
