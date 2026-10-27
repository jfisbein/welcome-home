package welcomehome.nuki;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import welcomehome.IntegrationTest;

/** Same configuration as the locks tests, so they share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class NukiWebhookTests {

    /** Nuki's example for checking a signature (https://docs.nuki.io/guide/webhooks/monitoring). */
    static final String NUKIS_SECRET = "mySecret123";
    static final String NUKIS_BODY = "{\"hello\":\"world\"}";
    static final String NUKIS_SIGNATURE = "df46b477700fde714a53eca6cd37bc5480d0e0ee95037e4970da95e8bdfc4cfa";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ActionReports reports;

    @Test
    void theSignatureIsTheBodysHmacSha256WithTheSecretAsNukiComputesIt() {
        var body = NUKIS_BODY.getBytes(StandardCharsets.UTF_8);

        assertThat(NukiStandInConfiguration.hmacSha256(NUKIS_SECRET, NUKIS_BODY)).isEqualTo(NUKIS_SIGNATURE);
        assertThat(NukiWebhookController.signedWith(NUKIS_SECRET, body, NUKIS_SIGNATURE)).isTrue();
        assertThat(NukiWebhookController.signedWith(NUKIS_SECRET, body, " " + NUKIS_SIGNATURE.toUpperCase(Locale.ROOT) + " "))
                .isTrue();
        assertThat(NukiWebhookController.signedWith(NUKIS_SECRET, "{\"hello\": \"world\"}".getBytes(StandardCharsets.UTF_8),
                NUKIS_SIGNATURE)).as("the same JSON, with a space more").isFalse();
        assertThat(NukiWebhookController.signedWith("another secret", body, NUKIS_SIGNATURE)).isFalse();
        assertThat(NukiWebhookController.signedWith(NUKIS_SECRET, body, "9ed1a5b4465ffc9c238a03096b2f49fdbcd3527a"))
                .as("the HMAC-SHA1 of X-Nuki-Signature").isFalse();
        assertThat(NukiWebhookController.signedWith(NUKIS_SECRET, body, null)).isFalse();
    }

    @Test
    void aSignedReportGivesTheOutcomeOfTheActionWithItsRequestId() {
        var requestId = requestId();
        var waiting = reports.pending(requestId);
        var other = reports.pending(requestId());

        assertThat(send(report(requestId, true, null))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(waiting.awaitOutcome(Duration.ZERO)).contains(LockActionState.SUCCEEDED);
        assertThat(other.awaitOutcome(Duration.ZERO)).isEmpty();
    }

    @Test
    void aReportThatArrivesBeforeNukisAnswerToTheRequestIsKept() {
        var requestId = requestId();

        assertThat(send(report(requestId, true, null))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(reports.pending(requestId).awaitOutcome(Duration.ZERO)).contains(LockActionState.SUCCEEDED);
    }

    /** Nuki's error codes come as hexadecimal text in its examples: night mode is 0x4F, a blocked motor 0x42. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            "4f"     | REJECTED_NIGHT_MODE
            "4F"     | REJECTED_NIGHT_MODE
            "0x4F"   | REJECTED_NIGHT_MODE
            79       | REJECTED_NIGHT_MODE
            "42"     | MOTOR_BLOCKED
            "ff"     | FAILED
            "45"     | FAILED
            "nope"   | FAILED
            """)
    void aFailureIsToldByItsErrorCode(String errorCode, LockActionState state) {
        var atTheTop = requestId();
        var inTheDetails = requestId();

        send(report(atTheTop, false, errorCode));
        send("""
                {"detail": [{"smartlockId": 17, "success": false, "errorCode": %s, "id": "d-1"}],
                 "type": "SmartlockAction", "requestId": "%s", "success": false}
                """.formatted(errorCode, inTheDetails));

        assertThat(reports.pending(atTheTop).awaitOutcome(Duration.ZERO)).contains(state);
        assertThat(reports.pending(inTheDetails).awaitOutcome(Duration.ZERO)).contains(state);
    }

    @Test
    void aFailureWithoutAnErrorCodeIsAFailure() {
        var requestId = requestId();

        send(report(requestId, false, null));

        assertThat(reports.pending(requestId).awaitOutcome(Duration.ZERO)).contains(LockActionState.FAILED);
    }

    @Test
    void webhooksThatNukiDidNotSignAreRefusedAndChangeNothing() {
        var requestId = requestId();
        var body = report(requestId, true, null);

        assertThat(post(body, null)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(post(body, NukiStandInConfiguration.signature(report(requestId, false, null))))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(post(body, NukiStandInConfiguration.hmacSha256("not the client's secret", body)))
                .hasStatus(HttpStatus.UNAUTHORIZED);

        assertThat(reports.pending(requestId).awaitOutcome(Duration.ZERO)).isEmpty();
    }

    /** Nuki turns off the webhooks of a client that fails too many, so whatever it signs is answered with 204. */
    @Test
    void nukisTestWebhookAndWebhooksAboutDevicesAreAnsweredAndIgnored() {
        assertThat(send("{}")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(send("""
                {"feature": "DEVICE_LOGS", "smartlockLog": {"id": "l-1", "smartlockId": 17, "deviceType": 4,
                 "name": "Nuki Web", "action": 3, "trigger": 0, "state": 0, "date": "2026-10-27T12:25:40.000Z"}}
                """)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(send("not json")).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void anOversizedBodyIsNotRead() {
        var body = "{\"padding\": \"" + "x".repeat(NukiWebhookController.MAX_BODY_SIZE) + "\"}";

        assertThat(send(body)).hasStatus(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void aWebhookStartsNoSession() {
        var result = send(report(requestId(), true, null));

        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @Test
    void waitingForAReportThatDoesNotComeWaitsTheTimeout() {
        var waited = System.nanoTime();

        Optional<LockActionState> outcome = reports.pending(requestId()).awaitOutcome(Duration.ofMillis(200));

        assertThat(outcome).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - waited)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    }

    private MvcTestResult send(String body) {
        return post(body, NukiStandInConfiguration.signature(body));
    }

    private MvcTestResult post(String body, @Nullable String signature) {
        var request = mvc.post().uri("/nuki/webhook")
                .contentType("application/json; charset=UTF-8")
                .content(body);
        return (signature == null ? request : request.header(NukiWebhookController.SIGNATURE_HEADER, signature))
                .exchange();
    }

    /** As Nuki's example of an Advanced API report, with an error code, as JSON, when one is given. */
    private static String report(String requestId, boolean success, @Nullable String errorCode) {
        return """
                {"type": "SmartlockAction", "requestId": "%s", "smartlockId": 17, "success": %s%s}
                """.formatted(requestId, success, errorCode == null ? "" : ", \"errorCode\": " + errorCode);
    }

    /** Reports live in memory for the whole context: each test uses its own request ids. */
    private static String requestId() {
        return UUID.randomUUID().toString();
    }
}
