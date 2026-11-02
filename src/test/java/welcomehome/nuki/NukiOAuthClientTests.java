package welcomehome.nuki;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.system.OutputCaptureExtension;
import welcomehome.nuki.LockProviderException.Reason;

@ExtendWith(OutputCaptureExtension.class)
class NukiOAuthClientTests {

    static final int MAX_ATTEMPTS = 3;
    static final Duration READ_TIMEOUT = Duration.ofMillis(500);
    static final String CLIENT_ID = "client-id";
    static final String CLIENT_SECRET = "client-secret";
    static final URI CALLBACK = URI.create("https://welcome.example/nuki/callback");
    static final String SCOPE = "smartlock.readOnly smartlock.action smartlock.log offline_access";

    @RegisterExtension
    static WireMockExtension nuki = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort())
            .build();

    NukiOAuthClient client;

    @BeforeEach
    void connectToTheStandIn() {
        client = clientFor(URI.create(nuki.baseUrl()), new NukiProperties.OAuth(CLIENT_ID, CLIENT_SECRET));
    }

    @Test
    void sendsTheOwnerToAuthorizeWhatWelcomeHomeUsesWithThePkceChallenge() {
        // The verifier and its S256 challenge from RFC 7636, appendix B.
        assertThat(client.authorizationUri(CALLBACK, "the-state", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", true))
                .hasToString(nuki.baseUrl()
                        + "/oauth/authorize?response_type=code&client_id=client-id"
                        + "&redirect_uri=https%3A%2F%2Fwelcome.example%2Fnuki%2Fcallback"
                        + "&scope=smartlock.readOnly%20smartlock.action%20smartlock.log%20offline_access"
                        + "%20webhook.central"
                        + "&state=the-state&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
                        + "&code_challenge_method=S256");
    }

    @Test
    void asksForWebhooksOnlyWhenTold() {
        assertThat(client.authorizationUri(CALLBACK, "the-state", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", false))
                .hasToString(nuki.baseUrl()
                        + "/oauth/authorize?response_type=code&client_id=client-id"
                        + "&redirect_uri=https%3A%2F%2Fwelcome.example%2Fnuki%2Fcallback"
                        + "&scope=smartlock.readOnly%20smartlock.action%20smartlock.log%20offline_access"
                        + "&state=the-state&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
                        + "&code_challenge_method=S256");
    }

    @Test
    void exchangesTheCodeWithTheVerifierAndTheClientsCredentialsInBothPlaces() {
        nuki.stubFor(post("/oauth/token").willReturn(okJson(tokens("access-1", "refresh-1"))));

        assertThat(client.exchangeCode("the-code", "the-verifier", CALLBACK))
                .isEqualTo(new NukiTokens("access-1", "refresh-1", Duration.ofSeconds(3599), SCOPE));
        nuki.verify(postRequestedFor(urlPathEqualTo("/oauth/token"))
                .withHeader("Authorization", equalTo("Basic " + Base64.getEncoder()
                        .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8))))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withFormParam("grant_type", equalTo("authorization_code"))
                .withFormParam("code", equalTo("the-code"))
                .withFormParam("code_verifier", equalTo("the-verifier"))
                .withFormParam("redirect_uri", equalTo(CALLBACK.toString()))
                .withFormParam("client_id", equalTo(CLIENT_ID))
                .withFormParam("client_secret", equalTo(CLIENT_SECRET)));
    }

    @Test
    void refreshesWithTheRefreshToken() {
        nuki.stubFor(post("/oauth/token").willReturn(okJson(tokens("access-2", "refresh-2"))));

        assertThat(client.refresh("refresh-1"))
                .isEqualTo(new NukiTokens("access-2", "refresh-2", Duration.ofSeconds(3599), SCOPE));
        nuki.verify(postRequestedFor(urlPathEqualTo("/oauth/token"))
                .withFormParam("grant_type", equalTo("refresh_token"))
                .withFormParam("refresh_token", equalTo("refresh-1"))
                .withFormParam("client_id", equalTo(CLIENT_ID))
                .withFormParam("client_secret", equalTo(CLIENT_SECRET)));
    }

    @Test
    void refusedCodeOrRefreshTokenIsAccessDenied() {
        nuki.stubFor(post("/oauth/token").willReturn(status(400).withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"invalid_grant\"}")));

        assertFailsWith(Reason.ACCESS_DENIED, () -> client.exchangeCode("used-code", "the-verifier", CALLBACK));
        assertFailsWith(Reason.ACCESS_DENIED, () -> client.refresh("revoked"));

        nuki.verify(2, postRequestedFor(urlPathEqualTo("/oauth/token")));
    }

    @Test
    void refusalOfTheClientIsNotTheOwnersAndIsNotRetried() {
        nuki.stubFor(post("/oauth/token").willReturn(status(401)));
        assertFailsWith(Reason.NUKI_ERROR, () -> client.refresh("fine"));
        assertFailsWith(Reason.NUKI_ERROR, () -> client.exchangeCode("fine", "the-verifier", CALLBACK));
        nuki.verify(2, postRequestedFor(urlPathEqualTo("/oauth/token")));

        nuki.stubFor(post("/oauth/token").willReturn(status(400).withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"invalid_client\"}")));
        assertFailsWith(Reason.NUKI_ERROR, () -> client.refresh("fine"));

        nuki.stubFor(post("/oauth/token").willReturn(status(400).withBody("not json")));
        assertFailsWith(Reason.ACCESS_DENIED, () -> client.refresh("revoked"));
    }

    @Test
    void revokingAskedOfAClientlessInstallAsksNothing() {
        var clientless = clientFor(URI.create(nuki.baseUrl()), new NukiProperties.OAuth("", ""));

        clientless.revoke("refresh-1");

        nuki.verify(0, postRequestedFor(urlPathEqualTo("/oauth2/revoke")));
    }

    @Test
    void revokingIsNotSentAgainAndTellsIfNukiDoesNotConfirmIt() {
        nuki.stubFor(post("/oauth2/revoke").willReturn(status(503)));

        assertFailsWith(Reason.NO_ANSWER, () -> client.revoke("refresh-1"));

        nuki.verify(1, postRequestedFor(urlPathEqualTo("/oauth2/revoke")));
    }

    @Test
    void tokenRequestThatNukiMayHaveAnsweredIsNotSentAgain() {
        nuki.stubFor(post("/oauth/token").willReturn(status(503)));
        assertFailsWith(Reason.NO_ANSWER, () -> client.refresh("refresh-1"));
        nuki.verify(1, postRequestedFor(urlPathEqualTo("/oauth/token")));

        nuki.resetAll();
        nuki.stubFor(post("/oauth/token").willReturn(slow(okJson(tokens("access-2", "refresh-2")))));
        assertFailsWith(Reason.NO_ANSWER, () -> client.refresh("refresh-1"));
        nuki.verify(1, postRequestedFor(urlPathEqualTo("/oauth/token")));

        nuki.resetAll();
        nuki.stubFor(post("/oauth/token").willReturn(status(429)));
        assertFailsWith(Reason.NUKI_ERROR, () -> client.refresh("refresh-1"));
        nuki.verify(MAX_ATTEMPTS, postRequestedFor(urlPathEqualTo("/oauth/token")));
    }

    @Test
    void answerWithoutABearerAccessTokenIsANukiError() {
        nuki.stubFor(post("/oauth/token").willReturn(okJson("""
                {"access_token": "access-1", "token_type": "mac", "expires_in": 3599}
                """)));
        assertFailsWith(Reason.NUKI_ERROR, () -> client.refresh("refresh-1"));

        nuki.stubFor(post("/oauth/token").willReturn(okJson("""
                {"token_type": "bearer", "expires_in": 3599}
                """)));
        assertFailsWith(Reason.NUKI_ERROR, () -> client.refresh("refresh-1"));
    }

    @Test
    void minimalAnswerLastsAnHourAndGrantsWhatWasAskedFor() {
        nuki.stubFor(post("/oauth/token").willReturn(okJson("""
                {"access_token": "access-1", "token_type": "Bearer"}
                """)));

        var tokens = client.refresh("refresh-1");

        assertThat(tokens).isEqualTo(new NukiTokens("access-1", null, Duration.ofHours(1), null));
        assertThat(tokens.grantsAll(NukiOAuthClient.SCOPES)).isTrue();
    }

    @Test
    void tokensTellWhetherEverythingAskedForWasGranted() {
        assertThat(new NukiTokens("a", "r", Duration.ZERO, " " + SCOPE + " ").grantsAll(NukiOAuthClient.SCOPES))
                .isTrue();
        assertThat(new NukiTokens("a", "r", Duration.ZERO, "smartlock.readOnly smartlock.log offline_access")
                .grantsAll(NukiOAuthClient.SCOPES)).isFalse();
    }

    @Test
    void tokensAreNeverPrinted() {
        assertThat(new NukiTokens("access-secret", "refresh-secret", Duration.ZERO, SCOPE).toString())
                .doesNotContain("access-secret", "refresh-secret");
    }

    private static String tokens(String accessToken, String refreshToken) {
        return """
                {"access_token": "%s", "token_type": "bearer", "expires_in": 3599, "refresh_token": "%s",
                 "scope": "%s"}
                """.formatted(accessToken, refreshToken, SCOPE);
    }

    private static NukiOAuthClient clientFor(URI baseUrl, NukiProperties.OAuth oauth) {
        var properties = new NukiProperties(baseUrl, Duration.ofSeconds(1), READ_TIMEOUT, MAX_ATTEMPTS,
                Duration.ofMillis(10), Duration.ofMillis(50), oauth, baseUrl.resolve("/oauth2/revoke"));
        return new NukiOAuthClient(new NukiRequests(properties), properties);
    }

    private static ResponseDefinitionBuilder slow(ResponseDefinitionBuilder response) {
        return response.withFixedDelay((int) READ_TIMEOUT.multipliedBy(2).toMillis());
    }

    private static void assertFailsWith(Reason reason, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(LockProviderException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
