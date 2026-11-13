package welcomehome.locks;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.icegreen.greenmail.util.GreenMail;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.crypto.CryptoService;
import welcomehome.notifications.MailStandInConfiguration;
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as NotificationTests, so both share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import({NukiStandInConfiguration.class, MailStandInConfiguration.class})
@TestPropertySource(properties = "spring.mail.host=127.0.0.1")
class LockProblemEmailTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    LockSyncJob job;

    @Autowired
    LockReachability reachability;

    @Autowired
    WireMockServer nuki;

    @Autowired
    GreenMail mail;

    @Autowired
    CryptoService crypto;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;
    UUID aliceId;
    UUID casa;

    @BeforeEach
    void connectAliceWithALock() throws Exception {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        data.deleteOwners();
        aliceId = data.owner(ALICE);
        data.apiToken(aliceId, crypto.encryptAccessToken(aliceId, "alices-token"));
        casa = data.lock(aliceId, 17, "Casa");
        mail.purgeEmailFromAllMailboxes();
    }

    @Test
    void aCriticalBatteryIsToldOnceWhileItLastsAndAgainIfItComesBack() throws Exception {
        nukiReports(true, true);
        job.syncAll();

        var email = emails(1)[0];
        assertThat(email.getSubject()).isEqualTo("Casa: la cerradura necesita que hagas algo");
        assertThat((String) email.getContent()).contains("La batería está crítica",
                IntegrationTest.BASE_URL + "/locks/" + casa, IntegrationTest.BASE_URL + "/settings/notifications")
                .doesNotContain("no puede comunicarse con ella");

        job.syncAll();
        emails(1);

        nukiReports(true, false);
        job.syncAll();
        emails(1);

        nukiReports(true, true);
        job.syncAll();
        emails(2);
    }

    @Test
    void aProblemThatJoinsAnotherIsToldAlone() throws Exception {
        nukiReports(true, true);
        job.syncAll();
        emails(1);

        nukiReports(false, true);
        job.syncAll();

        var second = emails(2)[1];
        assertThat((String) second.getContent()).contains("Nuki indica que no puede comunicarse con ella")
                .doesNotContain("La batería está crítica");
    }

    @Test
    void aLockReportedOfflineThatHasOpenedSinceIsNoProblemUntilAnOpeningFails() {
        nukiReports(false, false);
        job.syncAll();
        emails(1);

        reachability.opened(aliceId, casa);
        job.syncAll();
        emails(1);
        assertThat(migrator.sql("SELECT notified_problems FROM lock WHERE id = ?").param(casa)
                .query((rs, row) -> String.valueOf(rs.getString(1))).single()).isEqualTo("null");

        reachability.failedToOpen(aliceId, casa);
        job.syncAll();
        emails(2);
    }

    @Test
    void anOwnerWhoDoesNotWantThemGetsNone() {
        migrator.sql("INSERT INTO notification_preference (owner_id, lock_problems) VALUES (?, false)")
                .param(aliceId).update();
        nukiReports(false, true);

        job.syncAll();

        emails(0);
    }

    @Test
    void aLockWithoutProblemsAndNukiFailingSendNothing() {
        nukiReports(true, false);
        job.syncAll();
        emails(0);

        nuki.stubFor(get("/smartlock").willReturn(status(503)));
        job.syncAll();
        emails(0);
    }

    private void nukiReports(boolean online, boolean batteryCritical) {
        nuki.stubFor(get("/smartlock").willReturn(okJson("""
                [{"smartlockId": 17, "type": 5, "name": "Casa", "serverState": %d,
                  "state": {"state": 1, "batteryCritical": %s}, "advancedConfig": {"autoLock": true}}]
                """.formatted(online ? 0 : 4, batteryCritical))));
    }

    /** Waits for that many emails in all, and a little more for any still on its way. */
    private MimeMessage[] emails(int count) {
        await().atMost(Duration.ofSeconds(5)).until(() -> mail.getReceivedMessages().length >= count);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> mail.getReceivedMessages().length <= count);
        assertThat(mail.getReceivedMessages()).hasSize(count);
        return mail.getReceivedMessages();
    }
}
