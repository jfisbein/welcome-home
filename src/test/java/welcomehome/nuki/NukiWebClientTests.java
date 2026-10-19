package welcomehome.nuki;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import welcomehome.nuki.LockProviderException.Reason;

class NukiWebClientTests {

    static final String TOKEN = "test-api-token";
    static final long LOCK_ID = 17;
    static final int MAX_ATTEMPTS = 3;
    static final Duration READ_TIMEOUT = Duration.ofMillis(500);

    @RegisterExtension
    static WireMockExtension nuki = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    NukiWebClient client;

    @BeforeEach
    void connectToTheStandIn() {
        client = clientFor(URI.create(nuki.baseUrl()));
    }

    @Test
    void listsTheSmartLocksOfTheAccountButNotOpenersOrBoxes() {
        nuki.stubFor(get("/smartlock").withHeader("Authorization", equalTo("Bearer " + TOKEN)).willReturn(okJson("""
                [
                  {"smartlockId": 17, "type": 5, "name": "Casa", "serverState": 0, "config": {"timezoneId": 37}},
                  {"smartlockId": 18, "type": 4, "name": "Garaje", "serverState": 0, "config": {"timezoneId": 65535}},
                  {"smartlockId": 19, "type": 2, "name": "Portal", "serverState": 0},
                  {"smartlockId": 20, "type": 1, "name": "Buzón", "serverState": 0}
                ]
                """)));

        assertThat(client.listLocks(TOKEN))
                .containsExactly(new SmartLock(17, "Casa", "Europe/Berlin"), new SmartLock(18, "Garaje", null));
    }

    @Test
    void readsTheStateOfALock() {
        nuki.stubFor(get("/smartlock/17").willReturn(okJson("""
                {
                  "smartlockId": 17, "type": 5, "name": "Casa", "serverState": 0,
                  "state": {"mode": 2, "state": 1, "batteryCritical": false, "batteryCharge": 52, "doorState": 2},
                  "advancedConfig": {"autoLock": true, "autoLockTimeout": 300},
                  "config": {"name": "Casa", "autoUnlatch": false}
                }
                """)));

        assertThat(client.getState(TOKEN, LOCK_ID)).isEqualTo(new LockState(LockStatus.LOCKED, true, false, 52, true));
    }

    @Test
    void lockThatIsOfflineWithoutBatteryLevelOrAutoLock() {
        nuki.stubFor(get("/smartlock/17").willReturn(okJson("""
                {
                  "smartlockId": 17, "type": 0, "name": "Casa", "serverState": 4,
                  "state": {"state": 3, "batteryCritical": true},
                  "advancedConfig": {"autoLockTimeout": 0}
                }
                """)));

        assertThat(client.getState(TOKEN, LOCK_ID))
                .isEqualTo(new LockState(LockStatus.UNLOCKED, false, true, null, false));
    }

    @ParameterizedTest
    @CsvSource({
            "0, UNKNOWN", "1, LOCKED", "2, MOVING", "3, UNLOCKED", "4, MOVING", "5, UNLATCHED",
            "6, UNLOCKED", "7, MOVING", "254, MOTOR_BLOCKED", "255, UNKNOWN"})
    void translatesNukiStates(int nukiState, LockStatus status) {
        assertThat(NukiWebClient.status(nukiState)).isEqualTo(status);
    }

    @Test
    void unlatchSendsActionThree() {
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(204)));

        client.unlatch(TOKEN, LOCK_ID);

        nuki.verify(1, postRequestedFor(urlPathEqualTo("/smartlock/17/action"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withRequestBody(equalToJson("{\"action\": 3}")));
    }

    @Test
    void readsTheLatestLogEntries() {
        nuki.stubFor(get(urlPathEqualTo("/smartlock/17/log")).withQueryParam("limit", equalTo("20")).willReturn(okJson("""
                [
                  {"id": "c", "smartlockId": 17, "date": "2026-10-17T11:03:45.000Z", "action": 240, "trigger": 0, "state": 0},
                  {"id": "b", "smartlockId": 17, "date": "2026-10-17T11:03:30.000Z", "action": 3, "trigger": 4, "state": 0},
                  {"id": "a", "smartlockId": 17, "date": "2026-10-17T09:00:00.000Z", "action": 1, "trigger": 5, "state": 1},
                  {"id": "y", "smartlockId": 17, "date": "2026-10-17T08:30:00.000Z", "action": 3, "trigger": 4, "state": 10},
                  {"id": "x", "smartlockId": 17, "date": "2026-10-17T08:15:00.000Z", "action": 3, "trigger": 4, "state": 4},
                  {"id": "z", "smartlockId": 17, "date": "2026-10-17T08:00:00.000Z", "action": 4, "trigger": 2, "state": 0}
                ]
                """)));

        assertThat(client.getRecentLog(TOKEN, LOCK_ID)).containsExactly(
                new LockLogEntry(Instant.parse("2026-10-17T11:03:45Z"), LockAction.DOOR_OPENED, LockActionState.SUCCEEDED),
                new LockLogEntry(Instant.parse("2026-10-17T11:03:30Z"), LockAction.UNLATCH, LockActionState.SUCCEEDED),
                new LockLogEntry(Instant.parse("2026-10-17T09:00:00Z"), LockAction.UNLOCK, LockActionState.MOTOR_BLOCKED),
                new LockLogEntry(Instant.parse("2026-10-17T08:30:00Z"), LockAction.UNLATCH, LockActionState.REJECTED_NIGHT_MODE),
                new LockLogEntry(Instant.parse("2026-10-17T08:15:00Z"), LockAction.UNLATCH, LockActionState.FAILED),
                new LockLogEntry(Instant.parse("2026-10-17T08:00:00Z"), LockAction.OTHER, LockActionState.SUCCEEDED));
    }

    @Test
    void rejectedTokenIsAccessDeniedAndNotRetried() {
        nuki.stubFor(get("/smartlock").willReturn(status(401)));
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(403)));

        assertFailsWith(Reason.ACCESS_DENIED, () -> client.listLocks(TOKEN));
        assertFailsWith(Reason.MISSING_PERMISSION, () -> client.unlatch(TOKEN, LOCK_ID));
        nuki.verify(1, getRequestedFor(urlPathEqualTo("/smartlock")));
        nuki.verify(1, postRequestedFor(urlPathEqualTo("/smartlock/17/action")));
    }

    @Test
    void unknownLockIsNotFound() {
        nuki.stubFor(get("/smartlock/17").willReturn(status(404)));

        assertFailsWith(Reason.LOCK_NOT_FOUND, () -> client.getState(TOKEN, LOCK_ID));
    }

    @Test
    void rateLimitedCallsAreRetried() {
        nuki.stubFor(get("/smartlock").inScenario("rate limit").whenScenarioStateIs(STARTED)
                .willReturn(status(429).withHeader("Retry-After", "0")).willSetStateTo("cooled down"));
        nuki.stubFor(get("/smartlock").inScenario("rate limit").whenScenarioStateIs("cooled down")
                .willReturn(okJson("[]")));
        nuki.stubFor(post("/smartlock/17/action").inScenario("rate limit action").whenScenarioStateIs(STARTED)
                .willReturn(status(429)).willSetStateTo("cooled down"));
        nuki.stubFor(post("/smartlock/17/action").inScenario("rate limit action").whenScenarioStateIs("cooled down")
                .willReturn(status(204)));

        assertThat(client.listLocks(TOKEN)).isEmpty();
        client.unlatch(TOKEN, LOCK_ID);

        nuki.verify(2, getRequestedFor(urlPathEqualTo("/smartlock")));
        nuki.verify(2, postRequestedFor(urlPathEqualTo("/smartlock/17/action")));
    }

    @Test
    void readsAreRetriedOnServerErrorsUntilTheyGiveUp() {
        nuki.stubFor(get("/smartlock/17").willReturn(status(503)));

        assertFailsWith(Reason.NUKI_ERROR, () -> client.getState(TOKEN, LOCK_ID));
        nuki.verify(MAX_ATTEMPTS, getRequestedFor(urlPathEqualTo("/smartlock/17")));
    }

    @Test
    void readThatTimesOutIsRetried() {
        nuki.stubFor(get("/smartlock/17").inScenario("slow").whenScenarioStateIs(STARTED)
                .willReturn(slow(okJson("{}"))).willSetStateTo("fast"));
        nuki.stubFor(get("/smartlock/17").inScenario("slow").whenScenarioStateIs("fast").willReturn(okJson("""
                {"smartlockId": 17, "type": 5, "name": "Casa", "serverState": 0, "state": {"state": 1}}
                """)));

        assertThat(client.getState(TOKEN, LOCK_ID).status()).isEqualTo(LockStatus.LOCKED);
        nuki.verify(2, getRequestedFor(urlPathEqualTo("/smartlock/17")));
    }

    @Test
    void readThatKeepsTimingOutIsAnErrorNotANoAnswer() {
        nuki.stubFor(get("/smartlock/17").willReturn(slow(okJson("{}"))));

        assertFailsWith(Reason.NUKI_ERROR, () -> client.getState(TOKEN, LOCK_ID));
        nuki.verify(MAX_ATTEMPTS, getRequestedFor(urlPathEqualTo("/smartlock/17")));
    }

    @Test
    void unlatchIsNotRetriedOnceNukiMayHaveRunIt() {
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(503)));
        nuki.stubFor(post("/smartlock/18/action").willReturn(slow(status(204))));

        assertFailsWith(Reason.NO_ANSWER, () -> client.unlatch(TOKEN, LOCK_ID));
        assertFailsWith(Reason.NO_ANSWER, () -> client.unlatch(TOKEN, 18));
        nuki.verify(1, postRequestedFor(urlPathEqualTo("/smartlock/17/action")));
        nuki.verify(1, postRequestedFor(urlPathEqualTo("/smartlock/18/action")));
    }

    @Test
    void unlatchThatNukiRefusesOrKeepsRateLimitingIsAnError() {
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(429)));
        nuki.stubFor(post("/smartlock/18/action").willReturn(status(400)));

        assertFailsWith(Reason.NUKI_ERROR, () -> client.unlatch(TOKEN, LOCK_ID));
        assertFailsWith(Reason.NUKI_ERROR, () -> client.unlatch(TOKEN, 18));
        nuki.verify(MAX_ATTEMPTS, postRequestedFor(urlPathEqualTo("/smartlock/17/action")));
        nuki.verify(1, postRequestedFor(urlPathEqualTo("/smartlock/18/action")));
    }

    @Test
    void unreachableNukiIsAnError() throws Exception {
        URI closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = URI.create("http://localhost:" + socket.getLocalPort());
        }
        var unreachable = clientFor(closedPort);

        assertFailsWith(Reason.NUKI_ERROR, () -> unreachable.listLocks(TOKEN));
        assertFailsWith(Reason.NUKI_ERROR, () -> unreachable.unlatch(TOKEN, LOCK_ID));
    }

    @Test
    void otherFailuresAreNukiErrors() {
        nuki.stubFor(get("/smartlock").willReturn(status(402)));
        nuki.stubFor(get("/smartlock/17").willReturn(okJson("not json")));

        assertFailsWith(Reason.NUKI_ERROR, () -> client.listLocks(TOKEN));
        assertFailsWith(Reason.NUKI_ERROR, () -> client.getState(TOKEN, LOCK_ID));
        nuki.verify(1, getRequestedFor(urlPathEqualTo("/smartlock")));
    }

    private static NukiWebClient clientFor(URI baseUrl) {
        return new NukiWebClient(new NukiProperties(baseUrl, Duration.ofSeconds(1), READ_TIMEOUT, MAX_ATTEMPTS,
                Duration.ofMillis(10), Duration.ofMillis(50)));
    }

    private static ResponseDefinitionBuilder slow(ResponseDefinitionBuilder response) {
        return response.withFixedDelay((int) READ_TIMEOUT.multipliedBy(2).toMillis());
    }

    private static void assertFailsWith(Reason reason, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(LockProviderException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
