package welcomehome.access;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.crypto.LinkTokenSigner;
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as the locks tests, so they share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class OpenDoorTests {

    static final String ALICE = "alice@example.com";
    static final String ALICES_NUKI_TOKEN = "alices-nuki-token";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    WireMockServer nuki;

    @Autowired
    LinkTokenSigner signer;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID lockId;
    /** Rate limits live in memory for the whole context: each test comes from its own IP address. */
    String ip;

    @BeforeEach
    void connectAliceWithALock() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        var aliceId = migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id").param(ALICE)
                .query(UUID.class).single();
        lockId = migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, 17, 'Casa', 'Europe/Madrid') RETURNING id
                        """)
                .param(aliceId).query(UUID.class).single();
        nuki.stubFor(get("/smartlock").willReturn(okJson("[]")));
        NukiStandInConfiguration.logIsReadable(nuki);
        assertThat(mvc.put().uri("/nuki/connection").with(user(ALICE).roles("OWNER")).with(csrf())
                .param("apiToken", ALICES_NUKI_TOKEN))
                .hasStatus(HttpStatus.FOUND);
        nuki.resetRequests();
        ip = "10.%d.%d.%d".formatted(rand(), rand(), rand());
    }

    @Test
    void activeLinkUnlatchesTheDoorAndTheLocksLogConfirmsIt() {
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(204)));
        lockLogShowsUnlatch(0);
        var link = insertLink("now() - interval '1 hour'", "now() + interval '1 day'");

        assertThat(open(signer.sign(link, 1))).hasStatusOk().bodyJson().extractingPath("$.result").isEqualTo("opened");

        nuki.verify(1, postRequestedFor(urlPathEqualTo("/smartlock/17/action"))
                .withHeader("Authorization", equalTo("Bearer " + ALICES_NUKI_TOKEN))
                .withRequestBody(equalToJson("{\"action\": 3}")));
        assertThat(lastEvent()).isEqualTo("OK|opened|" + ip + "|Guest browser");
    }

    @Test
    void confirmationWaitsUntilTheUnlatchShowsUpInTheLog() {
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(204)));
        nuki.stubFor(get(urlPathEqualTo("/smartlock/17/log")).inScenario("log").whenScenarioStateIs("Started")
                .willReturn(okJson(log(Instant.now().minus(Duration.ofHours(1)), 3, 0))).willSetStateTo("once"));
        nuki.stubFor(get(urlPathEqualTo("/smartlock/17/log")).inScenario("log").whenScenarioStateIs("once")
                .willReturn(okJson("not json")).willSetStateTo("twice"));
        nuki.stubFor(get(urlPathEqualTo("/smartlock/17/log")).inScenario("log").whenScenarioStateIs("twice")
                .willReturn(okJson(log(Instant.now(), 3, 0))));
        var link = insertLink("now() - interval '1 hour'", "now() + interval '1 day'");

        assertThat(open(signer.sign(link, 1))).hasStatusOk().bodyJson().extractingPath("$.result").isEqualTo("opened");

        nuki.verify(3, getRequestedFor(urlPathEqualTo("/smartlock/17/log")));
    }

    @Test
    void unlatchMissingFromTheLogIsNotConfirmed() {
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(204)));
        nuki.stubFor(get(urlPathEqualTo("/smartlock/17/log"))
                .willReturn(okJson(log(Instant.now().minus(Duration.ofHours(1)), 3, 0))));
        var link = insertLink("now() - interval '1 hour'", "now() + interval '1 day'");

        assertThat(open(signer.sign(link, 1))).hasStatus(HttpStatus.ACCEPTED)
                .bodyJson().extractingPath("$.result").isEqualTo("not-confirmed");
        assertThat(lastEvent()).startsWith("OK|not-confirmed|");
    }

    @Test
    void failedOrNightModeUnlatchesAreReported() {
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(204)));
        var link = insertLink("now() - interval '1 hour'", "now() + interval '1 day'");

        lockLogShowsUnlatch(1);
        assertThat(open(signer.sign(link, 1))).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .bodyJson().extractingPath("$.result").isEqualTo("lock-failed");
        assertThat(lastEvent()).startsWith("ERROR|lock-failed|");

        lockLogShowsUnlatch(10);
        assertThat(open(signer.sign(link, 1))).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .bodyJson().extractingPath("$.result").isEqualTo("night-mode");
        assertThat(lastEvent()).startsWith("ERROR|night-mode|");
    }

    @Test
    void invalidTokenOpensNothingAndRecordsNothing() {
        var link = insertLink("now() - interval '1 hour'", "now() + interval '1 day'");

        for (var token : new String[] {signer.sign(link, 2), signer.sign(UUID.randomUUID(), 1), "nonsense", ""}) {
            assertThat(open(token)).as(token).hasStatus(HttpStatus.NOT_FOUND)
                    .bodyJson().extractingPath("$.result").isEqualTo("invalid");
        }
        nuki.verify(0, anyRequestedFor(anyUrl()));
        assertThat(migrator.sql("SELECT count(*) FROM access_event").query(Long.class).single()).isZero();
    }

    @Test
    void revokedPendingOrExpiredLinksAreDeniedWithoutAskingNuki() {
        var revoked = insertLink("now() - interval '1 hour'", "now() + interval '1 day'");
        migrator.sql("UPDATE access_link SET revoked_at = now() WHERE id = ?").param(revoked).update();
        var pending = insertLink("now() + interval '1 hour'", "now() + interval '1 day'");
        var expired = insertLink("now() - interval '2 days'", "now() - interval '1 day'");

        assertDenied(revoked, HttpStatus.GONE, "revoked");
        assertDenied(pending, HttpStatus.FORBIDDEN, "not-yet-valid");
        assertDenied(expired, HttpStatus.GONE, "expired");
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void inactiveLockOrMissingNukiConnectionIsUnavailable() {
        var link = insertLink("now() - interval '1 hour'", "now() + interval '1 day'");

        migrator.sql("UPDATE lock SET active = false").update();
        assertDenied(link, HttpStatus.SERVICE_UNAVAILABLE, "unavailable");

        migrator.sql("UPDATE lock SET active = true").update();
        migrator.sql("UPDATE nuki_connection SET status = 'DISCONNECTED'").update();
        assertDenied(link, HttpStatus.SERVICE_UNAVAILABLE, "unavailable");

        migrator.sql("DELETE FROM nuki_connection").update();
        assertDenied(link, HttpStatus.SERVICE_UNAVAILABLE, "unavailable");
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void unlatchWithNoAnswerIsConfirmedFromTheLog() {
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(503)));
        var link = insertLink("now() - interval '1 hour'", "now() + interval '1 day'");

        lockLogShowsUnlatch(0);
        assertThat(open(signer.sign(link, 1))).hasStatusOk().bodyJson().extractingPath("$.result").isEqualTo("opened");
        assertThat(lastEvent()).startsWith("OK|opened|");

        nuki.stubFor(get(urlPathEqualTo("/smartlock/17/log"))
                .willReturn(okJson(log(Instant.now().minus(Duration.ofHours(1)), 3, 0))));
        assertThat(open(signer.sign(link, 1))).as("the door may have opened: the guest must not be told to retry")
                .hasStatus(HttpStatus.ACCEPTED).bodyJson().extractingPath("$.result").isEqualTo("not-confirmed");
        assertThat(lastEvent()).startsWith("OK|not-confirmed|");

        nuki.verify(2, postRequestedFor(urlPathEqualTo("/smartlock/17/action")));
    }

    @Test
    void nukiFailuresAreErrors() {
        var link = insertLink("now() - interval '1 hour'", "now() + interval '1 day'");

        nuki.stubFor(post("/smartlock/17/action").willReturn(status(429)));
        assertThat(open(signer.sign(link, 1))).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .bodyJson().extractingPath("$.result").isEqualTo("nuki-error");
        assertThat(lastEvent()).startsWith("ERROR|nuki-error|");
        nuki.verify(0, getRequestedFor(urlPathEqualTo("/smartlock/17/log")));

        nuki.stubFor(post("/smartlock/17/action").willReturn(status(401)));
        assertThat(open(signer.sign(link, 1))).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .bodyJson().extractingPath("$.result").isEqualTo("unavailable");
        assertThat(migrator.sql("SELECT status FROM nuki_connection").query(String.class).single())
                .as("Nuki rejecting the owner's token disconnects it")
                .isEqualTo("DISCONNECTED");
    }

    @Test
    void anUnlatchNukiRefusesForLackOfPermissionIsToldAsSuch() {
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(403)));
        var link = insertLink("now() - interval '1 hour'", "now() + interval '1 day'");

        assertThat(open(signer.sign(link, 1))).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .bodyJson().extractingPath("$.result").isEqualTo("missing-permission");
        assertThat(lastEvent()).startsWith("ERROR|missing-permission|");
        assertThat(migrator.sql("SELECT status FROM nuki_connection").query(String.class).single())
                .as("a missing permission does not disconnect").isEqualTo("OK");
    }

    @Test
    void aLogThatMayNotBeReadEndsTheWaitAtOnce() {
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(204)));
        nuki.stubFor(get(urlPathEqualTo("/smartlock/17/log")).willReturn(status(403)));
        var link = insertLink("now() - interval '1 hour'", "now() + interval '1 day'");

        assertThat(open(signer.sign(link, 1))).hasStatus(HttpStatus.ACCEPTED)
                .bodyJson().extractingPath("$.result").isEqualTo("not-confirmed");
        nuki.verify(1, getRequestedFor(urlPathEqualTo("/smartlock/17/log")));
    }

    @Test
    void theGuestsIpAddressIsTheOneCloudflareReports() {
        var link = insertLink("now() - interval '2 days'", "now() - interval '1 day'");

        assertThat(mvc.post().uri("/api/open").with(csrf())
                .header("CF-Connecting-IP", ip)
                .header("X-Forwarded-For", "203.0.113.66")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + signer.sign(link, 1) + "\"}"))
                .hasStatus(HttpStatus.GONE);

        assertThat(lastEvent()).as("X-Forwarded-For starts with whatever the client sent")
                .isEqualTo("DENIED|expired|" + ip);
    }

    @Test
    void anIpAddressGetsTenAttemptsPerMinute() {
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(open("nonsense")).as("attempt " + attempt).hasStatus(HttpStatus.NOT_FOUND);
        }

        assertThat(open("nonsense")).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .bodyJson().extractingPath("$.result").isEqualTo("too-many-attempts");
        var otherIp = ip;
        ip = "10.255.%d.%d".formatted(rand(), rand());
        assertThat(open("nonsense")).as("from " + ip + " after " + otherIp).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aLinkGetsFiveAttemptsPerMinuteFromAnyIpAddress() {
        var link = insertLink("now() + interval '1 hour'", "now() + interval '1 day'");

        for (int attempt = 1; attempt <= 5; attempt++) {
            ip = "10.254.%d.%d".formatted(rand(), rand());
            assertThat(open(signer.sign(link, 1))).as("attempt " + attempt).hasStatus(HttpStatus.FORBIDDEN);
        }

        ip = "10.253.%d.%d".formatted(rand(), rand());
        assertThat(open(signer.sign(link, 1))).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(lastEvent()).startsWith("DENIED|too-many-attempts|");
    }

    @Test
    void simultaneousOpeningsOfALockSendOneActionAndShareItsResult() throws Exception {
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(204).withFixedDelay(1000)));
        lockLogShowsUnlatch(0);
        var tokens = new String[] {
                signer.sign(insertLink("now() - interval '1 hour'", "now() + interval '1 day'"), 1),
                signer.sign(insertLink("now() - interval '1 hour'", "now() + interval '1 day'"), 1),
                signer.sign(insertLink("now() - interval '1 hour'", "now() + interval '1 day'"), 1)};

        assertThat(openAtOnce(tokens)).containsOnly("opened").hasSize(3);

        nuki.verify(1, postRequestedFor(urlPathEqualTo("/smartlock/17/action")));
        assertThat(migrator.sql("SELECT result FROM access_event").query(String.class).list())
                .as("each guest's attempt is recorded")
                .containsExactly("OK", "OK", "OK");
    }

    @Test
    void guestsWaitingForAFailedActionGetTheSameError() throws Exception {
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(400).withFixedDelay(1000)));
        var token = signer.sign(insertLink("now() - interval '1 hour'", "now() + interval '1 day'"), 1);

        assertThat(openAtOnce(token, token)).containsOnly("nuki-error").hasSize(2);

        nuki.verify(1, postRequestedFor(urlPathEqualTo("/smartlock/17/action")));
    }

    @Test
    void openingRequiresTheCsrfTokenOfTheGuestPage() {
        var link = insertLink("now() - interval '1 hour'", "now() + interval '1 day'");

        assertThat(mvc.post().uri("/api/open").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + signer.sign(link, 1) + "\"}"))
                .hasStatus(HttpStatus.FORBIDDEN);
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    /** Opens with every token at the same time, each from its own IP address, and returns the results. */
    private List<String> openAtOnce(String... tokens) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(tokens.length)) {
            var results = new ArrayList<Future<String>>();
            for (var token : tokens) {
                var from = "10.252.%d.%d".formatted(rand(), rand());
                results.add(executor.submit(() -> {
                    start.await();
                    return JsonPath.read(body(open(token, from)), "$.result");
                }));
            }
            start.countDown();
            var codes = new ArrayList<String>();
            for (var result : results) {
                codes.add(result.get(10, TimeUnit.SECONDS));
            }
            return codes;
        }
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private MvcTestResult open(String token) {
        return open(token, ip);
    }

    private MvcTestResult open(String token, String from) {
        return mvc.post().uri("/api/open").with(csrf())
                .with(request -> {
                    request.setRemoteAddr(from);
                    return request;
                })
                .header("User-Agent", "Guest browser")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + token + "\"}")
                .exchange();
    }

    private void assertDenied(UUID link, HttpStatus status, String result) {
        assertThat(open(signer.sign(link, 1))).as(result).hasStatus(status)
                .bodyJson().extractingPath("$.result").isEqualTo(result);
        assertThat(lastEvent()).startsWith("DENIED|" + result + "|");
    }

    /** The lock's log shows an unlatch from now, which ended with the given Nuki completion state. */
    private void lockLogShowsUnlatch(int nukiState) {
        nuki.stubFor(get(urlPathEqualTo("/smartlock/17/log")).willReturn(okJson(log(Instant.now(), 3, nukiState))));
    }

    private static String log(Instant date, int nukiAction, int nukiState) {
        return """
                [{"id": "1", "smartlockId": 17, "date": "%s", "action": %d, "trigger": 4, "state": %d}]
                """.formatted(date, nukiAction, nukiState);
    }

    /** The latest event as "result|reason|ip|userAgent". */
    private String lastEvent() {
        return migrator.sql("""
                        SELECT concat_ws('|', result, reason, ip, user_agent) FROM access_event
                        ORDER BY occurred_at DESC LIMIT 1
                        """)
                .query(String.class).single();
    }

    private UUID insertLink(String validFrom, String validUntil) {
        return migrator.sql("INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until) "
                        + "SELECT owner_id, id, 'Ana', " + validFrom + ", " + validUntil + " FROM lock WHERE id = ? RETURNING id")
                .param(lockId).query(UUID.class).single();
    }

    private static int rand() {
        return ThreadLocalRandom.current().nextInt(1, 255);
    }
}
