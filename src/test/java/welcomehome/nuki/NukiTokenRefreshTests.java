package welcomehome.nuki;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.crypto.CryptoService;
import welcomehome.nuki.LockProviderException.Reason;

/**
 * An OAuth2 access token lasts an hour: it is refreshed before a call when it is about to expire, once however
 * many calls need it, and a refresh that Nuki refuses disconnects, as a rejected token does.
 * Same configuration as the locks tests, so they share one application context.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class NukiTokenRefreshTests {

    static final List<SmartLock> CASA = List.of(new SmartLock(17, DeviceType.SMART_LOCK, "Casa", null));

    @Autowired
    LockProvider locks;

    @Autowired
    ConnectionCheckJob job;

    @Autowired
    WireMockServer nuki;

    @Autowired
    CryptoService crypto;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID aliceId;

    @BeforeEach
    void insertAlice() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        aliceId = migrator.sql("INSERT INTO owner (email) VALUES ('alice@example.com') RETURNING id")
                .query(UUID.class).single();
    }

    @Test
    void accessTokenAboutToExpireIsRefreshedBeforeTheCall() {
        insertAuthorization("access-1", "refresh-1", "4 minutes");
        nukiRefreshes("refresh-1", "access-2", "refresh-2");
        nukiListsCasaFor("access-2");

        assertThat(locks.listLocks(aliceId)).isEqualTo(CASA);

        nuki.verify(postRequestedFor(urlPathEqualTo("/oauth/token"))
                .withFormParam("grant_type", equalTo("refresh_token"))
                .withFormParam("refresh_token", equalTo("refresh-1")));
        nuki.verify(0, getRequestedFor(urlPathEqualTo("/smartlock")).withHeader("Authorization",
                equalTo("Bearer access-1")));
        assertThat(crypto.decryptAccessToken(aliceId, column("access_token_encrypted"))).isEqualTo("access-2");
        assertThat(crypto.decryptRefreshToken(aliceId, column("refresh_token_encrypted"))).isEqualTo("refresh-2");
        assertThat(expiresAt()).isBetween(OffsetDateTime.now().plusSeconds(3599 - 60),
                OffsetDateTime.now().plusSeconds(3599));
    }

    @Test
    void accessTokenThatStillLastsIsUsedAsItIs() {
        insertAuthorization("access-1", "refresh-1", "6 minutes");
        nukiListsCasaFor("access-1");

        assertThat(locks.listLocks(aliceId)).isEqualTo(CASA);

        nuki.verify(0, postRequestedFor(urlPathEqualTo("/oauth/token")));
    }

    @Test
    void callsThatNeedARefreshAtOnceRefreshOnce() throws Exception {
        insertAuthorization("access-1", "refresh-1", "-1 minute");
        // Slow enough for every call to find the token expired before the refresh ends.
        nuki.stubFor(post("/oauth/token").willReturn(okJson(tokens("access-2", "refresh-2")).withFixedDelay(300)));
        nukiListsCasaFor("access-2");

        try (var executor = Executors.newFixedThreadPool(4)) {
            var calls = List.of(executor.submit(() -> locks.listLocks(aliceId)),
                    executor.submit(() -> locks.listLocks(aliceId)), executor.submit(() -> locks.listLocks(aliceId)),
                    executor.submit(() -> locks.listLocks(aliceId)));
            for (var call : calls) {
                assertThat(call.get()).isEqualTo(CASA);
            }
        }

        nuki.verify(1, postRequestedFor(urlPathEqualTo("/oauth/token")));
        nuki.verify(4, getRequestedFor(urlPathEqualTo("/smartlock")));
    }

    @Test
    void refreshThatNukiRefusesDisconnects() {
        insertAuthorization("access-1", "refresh-1", "-1 minute");
        nuki.stubFor(post("/oauth/token").willReturn(status(400).withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"invalid_grant\"}")));

        assertFailsWith(Reason.ACCESS_DENIED, () -> locks.listLocks(aliceId));

        assertThat(connectionStatus()).isEqualTo("DISCONNECTED");
        nuki.resetRequests();
        assertFailsWith(Reason.NOT_CONNECTED, () -> locks.listLocks(aliceId));
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void refreshThatNukiDoesNotAnswerKeepsTheConnectionAndOpensNothing() {
        insertAuthorization("access-1", "refresh-1", "-1 minute");
        nuki.stubFor(post("/oauth/token").willReturn(status(503)));
        nuki.stubFor(post(urlPathMatching("/smartlock/17/action")).willReturn(status(204)));

        // Not NO_ANSWER: a guest would be told that the door may have opened.
        assertFailsWith(Reason.NUKI_ERROR, () -> locks.unlatch(aliceId, 17));

        nuki.verify(0, postRequestedFor(urlPathEqualTo("/smartlock/17/action")));
        assertThat(connectionStatus()).isEqualTo("OK");
        assertThat(crypto.decryptRefreshToken(aliceId, column("refresh_token_encrypted"))).isEqualTo("refresh-1");
    }

    @Test
    void refreshThatReturnsNoRefreshTokenKeepsTheOne() {
        insertAuthorization("access-1", "refresh-1", "-1 minute");
        nuki.stubFor(post("/oauth/token").willReturn(okJson("""
                {"access_token": "access-2", "token_type": "bearer", "expires_in": 3599}
                """)));
        nukiListsCasaFor("access-2");

        assertThat(locks.listLocks(aliceId)).isEqualTo(CASA);

        assertThat(crypto.decryptRefreshToken(aliceId, column("refresh_token_encrypted"))).isEqualTo("refresh-1");
        assertThat(crypto.decryptAccessToken(aliceId, column("access_token_encrypted"))).isEqualTo("access-2");
    }

    @Test
    void accessTokenThatNukiRejectsDisconnectsWithoutARefresh() {
        insertAuthorization("access-1", "refresh-1", "1 hour");
        nuki.stubFor(get("/smartlock").willReturn(status(401)));

        assertFailsWith(Reason.ACCESS_DENIED, () -> locks.listLocks(aliceId));

        assertThat(connectionStatus()).isEqualTo("DISCONNECTED");
        nuki.verify(0, postRequestedFor(urlPathEqualTo("/oauth/token")));
    }

    @Test
    void pastedApiTokenIsNeverRefreshed() {
        migrator.sql("INSERT INTO nuki_connection (owner_id, access_token_encrypted, status) VALUES (?, ?, 'OK')")
                .params(aliceId, crypto.encryptAccessToken(aliceId, "api-token")).update();
        nukiListsCasaFor("api-token");

        assertThat(locks.listLocks(aliceId)).isEqualTo(CASA);

        nuki.verify(0, postRequestedFor(urlPathEqualTo("/oauth/token")));
    }

    @Test
    void dailyCheckRefreshesTheAccessToken() {
        insertAuthorization("access-1", "refresh-1", "-1 day");
        nukiRefreshes("refresh-1", "access-2", "refresh-2");
        nukiListsCasaFor("access-2");

        job.checkAll();

        assertThat(connectionStatus()).isEqualTo("OK");
        assertThat(crypto.decryptRefreshToken(aliceId, column("refresh_token_encrypted"))).isEqualTo("refresh-2");
        assertThat(migrator.sql("SELECT last_checked_at > now() - interval '1 minute' FROM nuki_connection")
                .query(Boolean.class).single()).isTrue();
    }

    private void insertAuthorization(String accessToken, String refreshToken, String expiresIn) {
        migrator.sql("""
                        INSERT INTO nuki_connection (owner_id, credential, access_token_encrypted,
                                                     refresh_token_encrypted, access_token_expires_at, status)
                        VALUES (?, 'OAUTH2', ?, ?, now() + ?::interval, 'OK')
                        """)
                .params(aliceId, crypto.encryptAccessToken(aliceId, accessToken),
                        crypto.encryptRefreshToken(aliceId, refreshToken), expiresIn)
                .update();
    }

    private void nukiRefreshes(String refreshToken, String accessToken, String newRefreshToken) {
        nuki.stubFor(post("/oauth/token").withFormParam("refresh_token", equalTo(refreshToken))
                .willReturn(okJson(tokens(accessToken, newRefreshToken))));
    }

    private void nukiListsCasaFor(String accessToken) {
        nuki.stubFor(get("/smartlock").withHeader("Authorization", equalTo("Bearer " + accessToken))
                .willReturn(okJson("[{\"smartlockId\": 17, \"type\": 5, \"name\": \"Casa\", \"serverState\": 0}]")));
    }

    private static String tokens(String accessToken, String refreshToken) {
        return """
                {"access_token": "%s", "token_type": "bearer", "expires_in": 3599, "refresh_token": "%s",
                 "scope": "smartlock.readOnly smartlock.action smartlock.log offline_access"}
                """.formatted(accessToken, refreshToken);
    }

    private byte[] column(String column) {
        return migrator.sql("SELECT " + column + " FROM nuki_connection").query(byte[].class).single();
    }

    private OffsetDateTime expiresAt() {
        return migrator.sql("SELECT access_token_expires_at FROM nuki_connection")
                .query((rs, row) -> rs.getObject(1, OffsetDateTime.class)).single();
    }

    private String connectionStatus() {
        return migrator.sql("SELECT status FROM nuki_connection").query(String.class).single();
    }

    private static void assertFailsWith(Reason reason, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(LockProviderException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
