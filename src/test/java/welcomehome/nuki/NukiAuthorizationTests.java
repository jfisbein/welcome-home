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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.BasicCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.SignedIn;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.crypto.CryptoService;

/**
 * Connecting Nuki with OAuth2, against the Nuki stand-in: the way to Nuki, the way back, and every way back
 * that must change nothing.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
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
    TestData data;
    UUID aliceId;
    MockHttpSession session;

    @BeforeEach
    void insertAliceAndBob() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        data.deleteOwners();
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
                + "&scope=smartlock.readOnly%20smartlock.action%20smartlock.log%20offline_access"
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
        assertThat(stored.status()).isEqualTo("OK");
        assertThat(new String(stored.accessToken(), StandardCharsets.ISO_8859_1)).doesNotContain("access-1");
        assertThat(new String(stored.refreshToken(), StandardCharsets.ISO_8859_1)).doesNotContain("refresh-1");
        assertThat(crypto.decryptAccessToken(aliceId, stored.accessToken())).isEqualTo("access-1");
        assertThat(crypto.decryptRefreshToken(aliceId, stored.refreshToken())).isEqualTo("refresh-1");
        assertThat(stored.expiresAt()).isBetween(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(3599 - 60),
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(3599));
        assertThat(mvc.get().uri("/settings/nuki").with(SignedIn.owner(ALICE)))
                .bodyText().contains("Conectado con tu cuenta de Nuki.")
                .doesNotContain("/settings/nuki/oauth", "Te llevamos a Nuki", "API token");
    }

    @Test
    void scopesThatNukiGrantsAreLoggedWhetherOrNotTheyAreEnough(CapturedOutput output) {
        nukiGrants("everything", "access-1", "refresh-1", SCOPE + " smartlock.auth");
        callback(ALICE, "code=everything&state=" + state(authorize(ALICE)));
        nuki.resetAll();
        nukiGrants("no-action", "access-2", "refresh-2", "smartlock.readOnly\\nsmartlock.log offline_access");
        assertFailed(callback(ALICE, "code=no-action&state=" + state(authorize(ALICE))), "missing-permission");

        assertThat(output.getAll())
                .contains("Nuki granted owner " + aliceId + " the scopes " + SCOPE + " smartlock.auth")
                .contains("Nuki granted owner " + aliceId + " the scopes smartlock.readOnly smartlock.log offline_access")
                .doesNotContain("access-1", "refresh-1", "access-2", "refresh-2");
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
    void codeThatNukiRefusesChangesNothing() {
        nuki.stubFor(post("/oauth/token").willReturn(status(400).withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"invalid_grant\"}")));

        assertFailed(callback(ALICE, "code=old-code&state=" + state(authorize(ALICE))), "failed");
        assertNoConnection();
    }

    @Test
    void ownerIsNotToldThatTheyFailedWhenNukiRefusesTheClient() {
        nuki.stubFor(post("/oauth/token").willReturn(status(401)));

        assertFailed(callback(ALICE, "code=the-code&state=" + state(authorize(ALICE))), "unavailable");
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
        nukiGrants("no-log-scope", "access-1", "refresh-1", "smartlock.readOnly smartlock.action offline_access");
        assertFailed(callback(ALICE, "code=no-log-scope&state=" + state(authorize(ALICE))), "missing-permission");

        nuki.resetAll();
        nukiGrants("no-refresh", "access-1", null, "smartlock.readOnly smartlock.action smartlock.log");
        assertFailed(callback(ALICE, "code=no-refresh&state=" + state(authorize(ALICE))), "missing-permission");

        nuki.resetAll();
        nukiGrants("no-log", "access-1", "refresh-1", SCOPE);
        nuki.stubFor(get(urlPathEqualTo("/smartlock/log")).willReturn(status(403)));
        assertFailed(callback(ALICE, "code=no-log&state=" + state(authorize(ALICE))), "missing-permission");

        assertNoConnection();
    }

    /** What Nuki granted would stay valid there, with its tokens thrown away here. */
    @Test
    void authorizationThatIsNotStoredIsRevokedAtNuki(CapturedOutput output) {
        nuki.stubFor(post("/oauth2/revoke").willReturn(status(200)));
        nukiGrants("no-action", "access-1", "refresh-1", "smartlock.readOnly smartlock.log offline_access");
        callback(ALICE, "code=no-action&state=" + state(authorize(ALICE)));
        nukiGrants("no-refresh", "access-2", null, "smartlock.readOnly smartlock.action smartlock.log");
        callback(ALICE, "code=no-refresh&state=" + state(authorize(ALICE)));
        nukiGrants("no-log", "access-3", "refresh-3", SCOPE);
        nuki.stubFor(get(urlPathEqualTo("/smartlock/log")).willReturn(status(403)));
        callback(ALICE, "code=no-log&state=" + state(authorize(ALICE)));

        assertThat(nuki.findAll(postRequestedFor(urlPathEqualTo("/oauth2/revoke"))).stream()
                .map(request -> request.formParameter("token").firstValue() + " as "
                        + request.formParameter("token_type_hint").firstValue()))
                .containsExactly("refresh-1 as refresh_token", "access-2 as access_token",
                        "refresh-3 as refresh_token");
        assertThat(output.getAll())
                .contains("Asked Nuki to revoke the authorization of owner " + aliceId + ", which was not stored")
                .doesNotContain("refresh-1", "access-2", "refresh-3");
    }

    @Test
    void authorizationThatNukiDoesNotRevokeFailsAsItWould(CapturedOutput output) {
        nuki.stubFor(post("/oauth2/revoke").willReturn(status(503)));
        nukiGrants("no-action", "access-1", "refresh-1", "smartlock.readOnly smartlock.log offline_access");

        assertFailed(callback(ALICE, "code=no-action&state=" + state(authorize(ALICE))), "missing-permission");
        assertThat(output.getAll())
                .contains("Could not revoke the Nuki authorization of owner " + aliceId + ", which was not stored");
    }

    @Test
    void authorizationThatIsStoredIsNotRevoked() {
        nukiGrants("the-code", "access-1", "refresh-1", SCOPE);

        callback(ALICE, "code=the-code&state=" + state(authorize(ALICE)));

        nuki.verify(0, postRequestedFor(urlPathEqualTo("/oauth2/revoke")));
    }

    @Test
    void authorizingAgainReplacesTheTokensOfTheOwnersOneConnection() {
        insertAuthorization(aliceId, "DISCONNECTED");
        nukiGrants("the-code", "access-1", "refresh-1", SCOPE);

        callback(ALICE, "code=the-code&state=" + state(authorize(ALICE)));

        assertThat(migrator.sql("SELECT count(*) FROM nuki_connection").query(Integer.class).single()).isEqualTo(1);
        assertThat(stored(aliceId).status()).isEqualTo("OK");
        assertThat(crypto.decryptAccessToken(aliceId, stored(aliceId).accessToken())).isEqualTo("access-1");
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

        assertThat(mvc.get().uri("/settings/nuki").with(SignedIn.owner(ALICE)))
                .bodyText().contains("Nuki ha rechazado la conexión", ">Volver a conectar con Nuki</a>");
        assertThat(mvc.get().uri("/").with(SignedIn.owner(ALICE)))
                .bodyText().contains("Nuki ha rechazado la conexión.", "Volver a conectar Nuki");
    }

    @Test
    void disconnectingDeletesTheTokensAndRevokesTheAuthorizationAtNuki(CapturedOutput output) {
        insertAuthorization(aliceId, "OK");
        nuki.stubFor(post("/oauth2/revoke").willReturn(status(200)));

        var result = mvc.delete().uri("/settings/nuki").with(SignedIn.owner(ALICE)).with(csrf()).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/settings/nuki");
        assertThat(result.getMvcResult().getFlashMap().get("disconnected")).isEqualTo(true);
        assertNoConnection();
        nuki.verify(1, postRequestedFor(urlPathEqualTo("/oauth2/revoke"))
                .withBasicAuth(new BasicCredentials(NukiStandInConfiguration.CLIENT_ID,
                        NukiStandInConfiguration.CLIENT_SECRET))
                .withFormParam("token", equalTo("refresh-1"))
                .withFormParam("token_type_hint", equalTo("refresh_token")));
        assertThat(output.getAll()).contains("Asked Nuki to revoke the authorization of owner " + aliceId)
                .doesNotContain("refresh-1");
    }

    @Test
    void disconnectingDeletesTheTokensEvenIfNukiDoesNotRevokeThem(CapturedOutput output) {
        insertAuthorization(aliceId, "OK");
        nuki.stubFor(post("/oauth2/revoke").willReturn(status(503)));

        var result = mvc.delete().uri("/settings/nuki").with(SignedIn.owner(ALICE)).with(csrf()).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertNoConnection();
        nuki.verify(1, postRequestedFor(urlPathEqualTo("/oauth2/revoke")));
        assertThat(output.getAll()).contains("Could not revoke the Nuki authorization of owner " + aliceId)
                .doesNotContain("refresh-1");
    }

    @Test
    void disconnectingAPastedTokenAsksNukiNothing() {
        data.connection(aliceId, crypto.encryptAccessToken(aliceId, "pasted"));

        assertThat(mvc.delete().uri("/settings/nuki").with(SignedIn.owner(ALICE)).with(csrf())).hasStatus(HttpStatus.FOUND);

        assertNoConnection();
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void deletingAnOwnerRevokesTheirAuthorizationAtNuki() {
        insertAuthorization(aliceId, "OK");
        nuki.stubFor(post("/oauth2/revoke").willReturn(status(200)));

        assertThat(mvc.delete().uri("/admin/owners").with(SignedIn.admin(BOB)).with(csrf())
                .param("email", ALICE)).hasStatus(HttpStatus.FOUND);

        nuki.verify(1, postRequestedFor(urlPathEqualTo("/oauth2/revoke"))
                .withFormParam("token", equalTo("refresh-1")));
        assertThat(migrator.sql("SELECT count(*) FROM owner WHERE email = ?").param(ALICE).query(Long.class).single())
                .isZero();
    }

    private String authorize(String email) {
        var result = mvc.get().uri("/settings/nuki/oauth").with(SignedIn.owner(email)).session(session).exchange();
        assertThat(result).hasStatus(HttpStatus.FOUND);
        var location = result.getMvcResult().getResponse().getRedirectedUrl();
        assertThat(location).isNotNull();
        return location;
    }

    private MvcTestResult callback(String email, String query) {
        return mvc.get().uri("/nuki/callback?" + query).with(SignedIn.owner(email)).session(session).exchange();
    }

    private String page(String authorizationError) throws Exception {
        var result = mvc.get().uri("/settings/nuki").with(SignedIn.owner(ALICE)).flashAttr("authorizationError",
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
        data.authorization(ownerId, crypto.encryptAccessToken(ownerId, "access-1"),
                crypto.encryptRefreshToken(ownerId, "refresh-1"), "1 hour", status);
    }

    private Stored stored(UUID ownerId) {
        return migrator.sql("""
                        SELECT status, access_token_encrypted, refresh_token_encrypted, access_token_expires_at
                        FROM nuki_connection WHERE owner_id = ?
                        """)
                .param(ownerId)
                .query((rs, row) -> new Stored(rs.getString(1), rs.getBytes(2), rs.getBytes(3),
                        rs.getObject(4, OffsetDateTime.class)))
                .single();
    }

    private void assertNoConnection() {
        assertThat(migrator.sql("SELECT count(*) FROM nuki_connection").query(Long.class).single()).isZero();
    }

    private UUID insertOwner(String email) {
        return data.owner(email);
    }

    @SuppressWarnings("ArrayRecordComponent")
    record Stored(String status, byte[] accessToken, byte[] refreshToken,
                  OffsetDateTime expiresAt) {
    }
}
