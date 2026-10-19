package welcomehome.nuki;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.crypto.CryptoService;

@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class NukiConnectionTests {

    static final String ALICE = "alice@example.com";
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    static final String NEW_TOKEN = "fedcba9876543210fedcba9876543210";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    WireMockServer nuki;

    @Autowired
    LockProvider locks;

    @Autowired
    CryptoService crypto;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID aliceId;

    @BeforeEach
    void insertAliceAndBob() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        aliceId = insertOwner(ALICE);
        insertOwner("bob@example.com");
    }

    @Test
    void anonymousVisitorIsSentToLogin() {
        assertThat(mvc.get().uri("/nuki/connection"))
                .hasStatus(HttpStatus.FOUND)
                .redirectedUrl().endsWith("/login");
    }

    @Test
    void ownerWithoutConnectionIsAskedForAToken() {
        assertThat(mvc.get().uri("/nuki/connection").with(owner(ALICE)))
                .hasStatusOk()
                .bodyText().contains("Todavía no has conectado", "API token");
    }

    @Test
    void howToGetATokenIsShownOpenUntilTheOwnerConnects() {
        assertThat(mvc.get().uri("/nuki/connection").with(owner(ALICE)))
                .bodyText().contains("<details open", "Cómo conseguir el API token",
                        "<a href=\"https://web.nuki.io\" target=\"_blank\" rel=\"noopener noreferrer\">",
                        "Generar token de API", "Ver dispositivos", "Gestionar dispositivos",
                        "Ver protocolos de cierre y obtener notificaciones de protocolo");

        nukiAccepts(TOKEN);
        connect(ALICE, TOKEN);

        assertThat(mvc.get().uri("/nuki/connection").with(owner(ALICE)))
                .bodyText().contains("Cómo conseguir el API token").doesNotContain("<details open");
    }

    @Test
    void tokenThatNukiAcceptsIsStoredEncrypted() {
        nukiAccepts(TOKEN);

        var result = connect(ALICE, "  " + TOKEN + "\n");

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/nuki/connection");
        assertThat(result.getMvcResult().getFlashMap().get("connectedLocks")).isEqualTo(List.of("Casa"));
        var stored = storedToken(aliceId);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(TOKEN);
        assertThat(crypto.decryptApiToken(aliceId, stored)).isEqualTo(TOKEN);
        assertThat(migrator.sql("SELECT status FROM nuki_connection").query(String.class).single()).isEqualTo("OK");
        assertThat(mvc.get().uri("/nuki/connection").with(owner(ALICE)))
                .bodyText().contains("Conectado.", "Última comprobación");
    }

    @Test
    void pageNamesTheLocksJustFound() {
        assertThat(mvc.get().uri("/nuki/connection").with(owner(ALICE)).flashAttr("connectedLocks", List.of("Casa")))
                .bodyText().contains("Cerraduras encontradas", "Casa");
    }

    @Test
    void htmlFormSendsThePutAsAPostWithHiddenMethod() {
        nukiAccepts(TOKEN);

        assertThat(mvc.post().uri("/nuki/connection").with(owner(ALICE)).with(csrf())
                .param("_method", "put").param("apiToken", TOKEN))
                .hasStatus(HttpStatus.FOUND);
        assertThat(storedToken(aliceId)).isNotNull();
    }

    @Test
    void tokenThatNukiRejectsIsNotStored() {
        nuki.stubFor(get("/smartlock").willReturn(status(401)));

        var result = connect(ALICE, TOKEN);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyText().contains("Nuki no ha aceptado este API token").doesNotContain(TOKEN);
        assertNoConnection();
    }

    @Test
    void tokenThatMayNotReadTheLogIsNotStored() {
        nukiAccepts(TOKEN);
        nuki.stubFor(get(urlPathEqualTo("/smartlock/log")).willReturn(status(403)));

        assertThat(connect(ALICE, TOKEN)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyText().contains("le falta alguno de los tres permisos").doesNotContain(TOKEN);
        assertNoConnection();
    }

    @Test
    void nothingIsStoredWhileNukiDoesNotAnswer() {
        nuki.stubFor(get("/smartlock").willReturn(status(503)));

        assertThat(connect(ALICE, TOKEN))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .bodyText().contains("Nuki no responde");
        assertNoConnection();
    }

    @Test
    void inputThatCannotBeATokenIsRejectedWithoutAskingNuki() {
        for (var input : new String[] {"", "   ", "two words", "x".repeat(1001)}) {
            assertThat(connect(ALICE, input)).as(input).hasStatus(HttpStatus.BAD_REQUEST);
        }
        nuki.verify(0, anyRequestedFor(anyUrl()));
        assertNoConnection();
    }

    @Test
    void newTokenReplacesTheOldOneAndReconnects() {
        nukiAccepts(TOKEN);
        connect(ALICE, TOKEN);
        migrator.sql("UPDATE nuki_connection SET status = 'DISCONNECTED'").update();
        assertThat(mvc.get().uri("/nuki/connection").with(owner(ALICE))).bodyText().contains("Nuki ha rechazado");

        nukiAccepts(NEW_TOKEN);
        assertThat(connect(ALICE, NEW_TOKEN)).hasStatus(HttpStatus.FOUND);

        assertThat(crypto.decryptApiToken(aliceId, storedToken(aliceId))).isEqualTo(NEW_TOKEN);
        assertThat(migrator.sql("SELECT status FROM nuki_connection").query(String.class).list()).containsExactly("OK");
    }

    @Test
    void ownerDoesNotSeeAnotherOwnersConnection() {
        nukiAccepts(TOKEN);
        connect("bob@example.com", TOKEN);

        assertThat(mvc.get().uri("/nuki/connection").with(owner(ALICE))).bodyText().contains("Todavía no has conectado");
    }

    @Test
    void connectingRequiresTheCsrfToken() {
        assertThat(mvc.put().uri("/nuki/connection").with(owner(ALICE)).param("apiToken", TOKEN))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void signedInUserWithoutOwnerIsForbidden() {
        assertThat(mvc.get().uri("/nuki/connection").with(owner("deleted@example.com"))).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void panelAsksAnOwnerWithoutConnectionToConnectNuki() {
        assertThat(mvc.get().uri("/").with(owner(ALICE))).bodyText().contains("Conecta tu cuenta de Nuki");
    }

    @Test
    void panelWarnsWhenNukiRejectedTheToken() {
        nukiAccepts(TOKEN);
        connect(ALICE, TOKEN);
        assertThat(mvc.get().uri("/").with(owner(ALICE)))
                .bodyText().doesNotContain("Conecta tu cuenta de Nuki", "Nuki ha rechazado");

        migrator.sql("UPDATE nuki_connection SET status = 'DISCONNECTED'").update();

        assertThat(mvc.get().uri("/").with(owner(ALICE))).bodyText().contains("Nuki ha rechazado tu API token");
    }

    @Test
    void disconnectingDeletesTheTokenButKeepsLocksAndLinks() {
        nukiAccepts(TOKEN);
        connect(ALICE, TOKEN);
        var lockId = migrator.sql("""
                        INSERT INTO lock (owner_id, connection_id, nuki_smartlock_id, name, time_zone)
                        SELECT owner_id, id, 17, 'Casa', 'Europe/Madrid' FROM nuki_connection
                        RETURNING id
                        """)
                .query(UUID.class)
                .single();
        migrator.sql("""
                        INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until)
                        VALUES (?, ?, 'Invitado', now(), now() + interval '1 day')
                        """)
                .params(aliceId, lockId)
                .update();
        var linkBefore = link();

        var result = mvc.delete().uri("/nuki/connection").with(owner(ALICE)).with(csrf()).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/nuki/connection");
        assertNoConnection();
        assertThat(migrator.sql("SELECT connection_id IS NULL FROM lock").query(Boolean.class).single()).isTrue();
        assertThat(link()).isEqualTo(linkBefore);
        nuki.resetRequests();
        assertThatThrownBy(() -> locks.unlatch(aliceId, 17))
                .isInstanceOfSatisfying(LockProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo(LockProviderException.Reason.NOT_CONNECTED));
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void pageConfirmsTheDisconnection() {
        assertThat(mvc.get().uri("/nuki/connection").with(owner(ALICE)).flashAttr("disconnected", true))
                .bodyText().contains("Nuki desconectado", "Todavía no has conectado");
    }

    @Test
    void htmlFormSendsTheDeleteAsAPostWithHiddenMethod() {
        nukiAccepts(TOKEN);
        connect(ALICE, TOKEN);

        assertThat(mvc.post().uri("/nuki/connection").with(owner(ALICE)).with(csrf()).param("_method", "delete"))
                .hasStatus(HttpStatus.FOUND);
        assertNoConnection();
    }

    @Test
    void disconnectingTouchesOnlyTheOwnersOwnConnection() {
        nukiAccepts(TOKEN);
        connect(ALICE, TOKEN);
        connect("bob@example.com", TOKEN);

        mvc.delete().uri("/nuki/connection").with(owner(ALICE)).with(csrf()).exchange();

        assertThat(migrator.sql("SELECT o.email FROM nuki_connection c JOIN owner o ON o.id = c.owner_id")
                .query(String.class).list()).containsExactly("bob@example.com");
    }

    @Test
    void disconnectingRequiresTheCsrfToken() {
        nukiAccepts(TOKEN);
        connect(ALICE, TOKEN);

        assertThat(mvc.delete().uri("/nuki/connection").with(owner(ALICE))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(storedToken(aliceId)).isNotNull();
    }

    /** Everything that decides a link's state and its URL. */
    private String link() {
        return migrator.sql("SELECT concat_ws('|', lock_id, token_version, valid_from, valid_until, revoked_at) FROM access_link")
                .query(String.class)
                .single();
    }

    private MvcTestResult connect(String email, String apiToken) {
        return mvc.put().uri("/nuki/connection").with(owner(email)).with(csrf()).param("apiToken", apiToken).exchange();
    }

    private void nukiAccepts(String token) {
        nuki.stubFor(get("/smartlock").withHeader("Authorization", equalTo("Bearer " + token))
                .willReturn(okJson("[{\"smartlockId\": 17, \"type\": 5, \"name\": \"Casa\", \"serverState\": 0}]")));
        nuki.stubFor(get(urlPathEqualTo("/smartlock/log")).withHeader("Authorization", equalTo("Bearer " + token))
                .withQueryParam("limit", equalTo("1"))
                .willReturn(okJson("[]")));
    }

    private byte[] storedToken(UUID ownerId) {
        return migrator.sql("SELECT api_token_encrypted FROM nuki_connection WHERE owner_id = ?")
                .param(ownerId)
                .query(byte[].class)
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
}
