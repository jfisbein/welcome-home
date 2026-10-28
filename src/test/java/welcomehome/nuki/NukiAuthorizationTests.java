package welcomehome.nuki;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.crypto.CryptoService;

/**
 * Connecting Nuki with OAuth2, against the Nuki stand-in: the way to Nuki, the way back, and every way back
 * that must change nothing.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class NukiAuthorizationTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";
    static final String CALLBACK = IntegrationTest.BASE_URL + "/nuki/callback";
    static final String SCOPE = "smartlock.readOnly smartlock.action smartlock.log offline_access";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    WireMockServer nuki;

    @Autowired
    CryptoService crypto;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID aliceId;
    MockHttpSession session;

    @BeforeEach
    void insertAliceAndBob() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        aliceId = insertOwner(ALICE);
        insertOwner(BOB);
        session = new MockHttpSession();
    }

    @Test
    void anonymousVisitorIsSentToLogin() {
        assertThat(mvc.get().uri("/settings/nuki/oauth")).hasStatus(HttpStatus.FOUND).redirectedUrl().endsWith("/login");
        assertThat(mvc.get().uri("/nuki/callback?code=c&state=s"))
                .hasStatus(HttpStatus.FOUND).redirectedUrl().endsWith("/login");
    }

    @Test
    void ownerIsSentToNukiWithAStateAndAPkceChallengeOfTheirOwn() {
        var location = authorize(ALICE);

        assertThat(location).startsWith(nuki.baseUrl() + "/oauth/authorize?response_type=code"
                + "&client_id=" + NukiStandInConfiguration.CLIENT_ID
                + "&redirect_uri=https%3A%2F%2Fwelcome.example%2Fnuki%2Fcallback"
                + "&scope=smartlock.readOnly%20smartlock.action%20smartlock.log%20offline_access%20webhook.central"
                + "&state=");
        assertThat(state(location)).matches("[A-Za-z0-9_-]{43}");
        assertThat(param(location, "code_challenge")).matches("[A-Za-z0-9_-]{43}");
        assertThat(param(location, "code_challenge_method")).isEqualTo("S256");
        session = new MockHttpSession();
        var another = authorize(ALICE);
        assertThat(state(another)).isNotEqualTo(state(location));
        assertThat(param(another, "code_challenge")).isNotEqualTo(param(location, "code_challenge"));
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void ownerWhoAuthorizesIsConnectedWithTheirTokensEncrypted() throws Exception {
        nukiGrants("the-code", "access-1", "refresh-1", SCOPE);
        var location = authorize(ALICE);

        var result = callback(ALICE, "code=the-code&state=" + state(location) + "&scope=" + SCOPE.replace(' ', '+'));

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/settings/nuki");
        assertThat(result.getMvcResult().getFlashMap().get("connectedLocks")).isEqualTo(List.of("Casa"));
        nuki.verify(postRequestedFor(urlPathEqualTo("/oauth/token"))
                .withFormParam("grant_type", equalTo("authorization_code"))
                .withFormParam("code", equalTo("the-code"))
                .withFormParam("code_verifier", matching("[A-Za-z0-9_-]{43}"))
                .withFormParam("redirect_uri", equalTo(CALLBACK)));
        var verifier = sentVerifier();
        assertThat(s256(verifier)).isEqualTo(param(location, "code_challenge"));
        assertThat(location).doesNotContain(verifier);
        var stored = stored(aliceId);
        assertThat(stored.credential()).isEqualTo("OAUTH2");
        assertThat(stored.status()).isEqualTo("OK");
        assertThat(new String(stored.accessToken(), StandardCharsets.ISO_8859_1)).doesNotContain("access-1");
        assertThat(new String(stored.refreshToken(), StandardCharsets.ISO_8859_1)).doesNotContain("refresh-1");
        assertThat(crypto.decryptAccessToken(aliceId, stored.accessToken())).isEqualTo("access-1");
        assertThat(crypto.decryptRefreshToken(aliceId, stored.refreshToken())).isEqualTo("refresh-1");
        assertThat(stored.expiresAt()).isBetween(OffsetDateTime.now().plusSeconds(3599 - 60),
                OffsetDateTime.now().plusSeconds(3599));
        assertThat(mvc.get().uri("/settings/nuki").with(owner(ALICE)))
                .bodyText().contains("Conectado con tu cuenta de Nuki.", "<summary>Usar un API token en su lugar</summary>")
                .doesNotContain("/settings/nuki/oauth", "Te llevamos a Nuki");
    }

    @Test
    void callbackThatThisSessionDidNotStartChangesNothing() {
        nukiGrants("the-code", "access-1", "refresh-1", SCOPE);

        assertFailed(callback(ALICE, "code=the-code&state=never-sent"), "failed");
        authorize(ALICE);
        assertFailed(callback(ALICE, "code=the-code&state=another-state"), "failed");
        authorize(ALICE);
        assertFailed(callback(ALICE, "code=the-code"), "failed");

        nuki.verify(0, postRequestedFor(urlPathEqualTo("/oauth/token")));
        assertNoConnection();
    }

    @Test
    void wayBackWorksOnce() {
        nukiGrants("the-code", "access-1", "refresh-1", SCOPE);
        var state = state(authorize(ALICE));
        callback(ALICE, "code=the-code&state=" + state);

        assertFailed(callback(ALICE, "code=the-code&state=" + state), "failed");

        nuki.verify(1, postRequestedFor(urlPathEqualTo("/oauth/token")));
    }

    @Test
    void stateThatAnotherOwnerStartedIsRefused() {
        nukiGrants("the-code", "access-1", "refresh-1", SCOPE);
        var state = state(authorize(BOB));

        assertFailed(callback(ALICE, "code=the-code&state=" + state), "failed");

        nuki.verify(0, postRequestedFor(urlPathEqualTo("/oauth/token")));
        assertNoConnection();
    }

    @Test
    void ownerWhoSaysNoAtNukiChangesNothing() {
        var state = state(authorize(ALICE));

        assertFailed(callback(ALICE, "error=access_denied&state=" + state), "cancelled");

        state = state(authorize(ALICE));
        assertFailed(callback(ALICE, "error=server_error&state=" + state), "failed");
        nuki.verify(0, anyRequestedFor(anyUrl()));
        assertNoConnection();
    }

    @Test
    void clientThatMayNotAskForWebhooksSendsTheOwnerToNukiAgainWithoutThemOnce() {
        var first = authorize(ALICE);

        var again = askedAgain(callback(ALICE, "error=invalid_scope&state=" + state(first)));

        assertThat(param(again, "scope")).isEqualTo(SCOPE.replace(" ", "%20"));
        assertThat(state(again)).isNotEqualTo(state(first));
        assertThat(param(again, "code_challenge")).isNotEqualTo(param(first, "code_challenge"));
        assertFailed(callback(ALICE, "error=invalid_scope&state=" + state(again)), "failed");
        nuki.verify(0, anyRequestedFor(anyUrl()));
        assertNoConnection();
    }

    @Test
    void ownerOfAClientThatMayNotAskForWebhooksIsConnectedWithoutThem() throws Exception {
        nukiGrants("the-code", "access-1", "refresh-1", SCOPE);
        var again = askedAgain(callback(ALICE, "error=invalid_scope&state=" + state(authorize(ALICE))));

        var result = callback(ALICE, "code=the-code&state=" + state(again));

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/settings/nuki");
        assertThat(s256(sentVerifier())).isEqualTo(param(again, "code_challenge"));
        assertThat(stored(aliceId).status()).isEqualTo("OK");
    }

    @Test
    void codeThatNukiRefusesChangesNothing() {
        nuki.stubFor(post("/oauth/token").willReturn(status(400).withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"invalid_grant\"}")));

        assertFailed(callback(ALICE, "code=old-code&state=" + state(authorize(ALICE))), "failed");
        assertNoConnection();
    }

    @Test
    void nothingIsStoredWhileNukiDoesNotAnswer() {
        nuki.stubFor(post("/oauth/token").willReturn(status(503)));

        assertFailed(callback(ALICE, "code=the-code&state=" + state(authorize(ALICE))), "unavailable");
        assertNoConnection();
    }

    @Test
    void authorizationWithoutEverythingWelcomeHomeUsesIsNotStored() {
        nukiGrants("no-action", "access-1", "refresh-1", "smartlock.readOnly smartlock.log offline_access");
        assertFailed(callback(ALICE, "code=no-action&state=" + state(authorize(ALICE))), "missing-permission");

        nuki.resetAll();
        nukiGrants("no-refresh", "access-1", null, "smartlock.readOnly smartlock.action smartlock.log");
        assertFailed(callback(ALICE, "code=no-refresh&state=" + state(authorize(ALICE))), "missing-permission");

        nuki.resetAll();
        nukiGrants("no-log", "access-1", "refresh-1", SCOPE);
        nuki.stubFor(get(urlPathEqualTo("/smartlock/log")).willReturn(status(403)));
        assertFailed(callback(ALICE, "code=no-log&state=" + state(authorize(ALICE))), "missing-permission");

        assertNoConnection();
    }

    @Test
    void authorizingReplacesAPastedTokenAndKeepsTheConnectionsLocks() {
        migrator.sql("INSERT INTO nuki_connection (owner_id, access_token_encrypted, status) VALUES (?, ?, 'OK')")
                .params(aliceId, crypto.encryptAccessToken(aliceId, "pasted-token")).update();
        var connectionId = migrator.sql("SELECT id FROM nuki_connection").query(UUID.class).single();
        assertThat(mvc.get().uri("/settings/nuki").with(owner(ALICE)))
                .bodyText().contains(">Usar tu cuenta de Nuki</a>");
        nukiGrants("the-code", "access-1", "refresh-1", SCOPE);

        callback(ALICE, "code=the-code&state=" + state(authorize(ALICE)));

        assertThat(migrator.sql("SELECT id FROM nuki_connection").query(UUID.class).single()).isEqualTo(connectionId);
        assertThat(stored(aliceId).credential()).isEqualTo("OAUTH2");
        assertThat(crypto.decryptAccessToken(aliceId, stored(aliceId).accessToken())).isEqualTo("access-1");
    }

    @Test
    void pastingATokenReplacesTheAuthorization() {
        insertAuthorization(aliceId, "OK");
        nuki.stubFor(get("/smartlock").withHeader("Authorization", equalTo("Bearer pasted-token"))
                .willReturn(okJson("[]")));
        NukiStandInConfiguration.logIsReadable(nuki);

        assertThat(mvc.put().uri("/settings/nuki").with(owner(ALICE)).with(csrf()).param("apiToken", "pasted-token"))
                .hasStatus(HttpStatus.FOUND);

        var stored = stored(aliceId);
        assertThat(stored.credential()).isEqualTo("API_TOKEN");
        assertThat(stored.refreshToken()).isNull();
        assertThat(stored.expiresAt()).isNull();
        assertThat(crypto.decryptAccessToken(aliceId, stored.accessToken())).isEqualTo("pasted-token");
    }

    @Test
    void pageTellsEachWayBackThatChangedNothing() throws Exception {
        assertThat(page("cancelled")).contains("Has cancelado la autorización en Nuki");
        assertThat(page("failed")).contains("No hemos podido completar la conexión con Nuki");
        assertThat(page("missing-permission")).contains("Nuki no ha dado a welcome-home todos los permisos");
        assertThat(page("unavailable")).contains("Nuki no responde ahora mismo");
    }

    @Test
    void ownerWhoseAuthorizationNukiRejectedIsAskedToConnectAgain() {
        insertAuthorization(aliceId, "DISCONNECTED");

        assertThat(mvc.get().uri("/settings/nuki").with(owner(ALICE)))
                .bodyText().contains("Nuki ha rechazado la conexión", ">Volver a conectar con Nuki</a>");
        assertThat(mvc.get().uri("/").with(owner(ALICE)))
                .bodyText().contains("Nuki ha rechazado la conexión.", "Volver a conectar Nuki");
    }

    @Test
    void disconnectingDeletesTheTokens() {
        insertAuthorization(aliceId, "OK");

        var result = mvc.delete().uri("/settings/nuki").with(owner(ALICE)).with(csrf()).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/settings/nuki");
        assertThat(result.getMvcResult().getFlashMap().get("disconnected")).isEqualTo("OAUTH2");
        assertNoConnection();
    }

    private String authorize(String email) {
        var result = mvc.get().uri("/settings/nuki/oauth").with(owner(email)).session(session).exchange();
        assertThat(result).hasStatus(HttpStatus.FOUND);
        var location = result.getMvcResult().getResponse().getRedirectedUrl();
        assertThat(location).isNotNull();
        return location;
    }

    /** Where the callback sent the owner back to Nuki. */
    private String askedAgain(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.FOUND);
        var location = result.getMvcResult().getResponse().getRedirectedUrl();
        assertThat(location).startsWith(nuki.baseUrl() + "/oauth/authorize?");
        return location;
    }

    private MvcTestResult callback(String email, String query) {
        return mvc.get().uri("/nuki/callback?" + query).with(owner(email)).session(session).exchange();
    }

    private String page(String authorizationError) throws Exception {
        var result = mvc.get().uri("/settings/nuki").with(owner(ALICE)).flashAttr("authorizationError",
                authorizationError).exchange();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static void assertFailed(MvcTestResult result, String error) {
        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/settings/nuki");
        assertThat(result.getMvcResult().getFlashMap().get("authorizationError")).isEqualTo(error);
    }

    private static String state(String location) {
        return param(location, "state");
    }

    private static String param(String location, String name) {
        var value = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst(name);
        assertThat(value).isNotNull();
        return value;
    }

    /** The PKCE verifier of the one code exchange that Nuki received. */
    private String sentVerifier() {
        var requests = nuki.findAll(postRequestedFor(urlPathEqualTo("/oauth/token")));
        assertThat(requests).hasSize(1);
        var verifier = UriComponentsBuilder.newInstance().query(requests.getFirst().getBodyAsString()).build()
                .getQueryParams().getFirst("code_verifier");
        assertThat(verifier).isNotNull();
        return verifier;
    }

    /** RFC 7636's S256, worked out here rather than with the code under test. */
    private static String s256(String verifier) throws NoSuchAlgorithmException {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    /** Nuki exchanges the code for the tokens, and the access token lists one lock and reads the log. */
    private void nukiGrants(String code, String accessToken, String refreshToken, String scope) {
        nuki.stubFor(post("/oauth/token").withFormParam("code", equalTo(code)).willReturn(okJson("""
                {"access_token": "%s", "token_type": "bearer", "expires_in": 3599, %s "scope": "%s"}
                """.formatted(accessToken, refreshToken == null ? "" : "\"refresh_token\": \"" + refreshToken + "\",",
                scope))));
        nuki.stubFor(get("/smartlock").withHeader("Authorization", equalTo("Bearer " + accessToken))
                .willReturn(okJson("[{\"smartlockId\": 17, \"type\": 5, \"name\": \"Casa\", \"serverState\": 0}]")));
        nuki.stubFor(get(urlPathEqualTo("/smartlock/log")).withHeader("Authorization", equalTo("Bearer " + accessToken))
                .willReturn(okJson("[]")));
    }

    private void insertAuthorization(UUID ownerId, String status) {
        migrator.sql("""
                        INSERT INTO nuki_connection (owner_id, credential, access_token_encrypted,
                                                     refresh_token_encrypted, access_token_expires_at, status)
                        VALUES (?, 'OAUTH2', ?, ?, now() + interval '1 hour', ?)
                        """)
                .params(ownerId, crypto.encryptAccessToken(ownerId, "access-1"),
                        crypto.encryptRefreshToken(ownerId, "refresh-1"), status)
                .update();
    }

    private Stored stored(UUID ownerId) {
        return migrator.sql("""
                        SELECT credential, status, access_token_encrypted, refresh_token_encrypted,
                               access_token_expires_at
                        FROM nuki_connection WHERE owner_id = ?
                        """)
                .param(ownerId)
                .query((rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getBytes(3), rs.getBytes(4),
                        rs.getObject(5, OffsetDateTime.class)))
                .single();
    }

    private void assertNoConnection() {
        assertThat(migrator.sql("SELECT count(*) FROM nuki_connection").query(Long.class).single()).isZero();
    }

    private UUID insertOwner(String email) {
        return migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id").param(email).query(UUID.class).single();
    }

    private static RequestPostProcessor owner(String email) {
        return user(email).roles("OWNER");
    }

    @SuppressWarnings("ArrayRecordComponent")
    record Stored(String credential, String status, byte[] accessToken, byte[] refreshToken,
                  OffsetDateTime expiresAt) {
    }
}
