package welcomehome.nuki;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.SignedIn;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
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
    TestData data;
    UUID aliceId;

    @BeforeEach
    void insertAliceAndBob() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        data.deleteOwners();
        aliceId = insertOwner(ALICE);
        insertOwner("bob@example.com");
    }

    @Test
    void anonymousVisitorIsSentToLogin() {
        assertThat(mvc.get().uri("/settings/nuki"))
                .hasStatus(HttpStatus.FOUND)
                .redirectedUrl().endsWith("/login");
    }

    @Test
    void ownerWithoutConnectionIsOfferedToConnectWithTheirNukiAccountAndNoOtherWay() {
        assertThat(mvc.get().uri("/settings/nuki").with(SignedIn.owner(ALICE)))
                .hasStatusOk()
                .bodyText().contains("Todavía no has conectado",
                        "<a href=\"/settings/nuki/oauth\" role=\"button\"", "Conectar con Nuki", "Te llevamos a Nuki")
                .doesNotContain("API token", "name=\"apiToken\"", "Desconectar Nuki");
    }

    @Test
    void aConnectedOwnerIsOfferedToDisconnectAndToConnectAgainOnlyOnceNukiRejectsThem() {
        nukiAccepts(TOKEN);
        connect(ALICE, TOKEN);

        assertThat(mvc.get().uri("/settings/nuki").with(SignedIn.owner(ALICE)))
                .bodyText().contains("Conectado con tu cuenta de Nuki.", "Última comprobación", "Desconectar Nuki")
                .doesNotContain("/settings/nuki/oauth");

        // In the server's zone and saying so, whatever zone the time was written in or the database session has.
        data.nukiLastChecked("'2030-07-01 12:00:00+02'");
        assertThat(mvc.get().uri("/settings/nuki").with(SignedIn.owner(ALICE)))
                .bodyText().contains("Última comprobación: " + DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm z", Locale.of("es"))
                        .format(Instant.parse("2030-07-01T10:00:00Z").atZone(ZoneId.systemDefault())) + ".");

        data.disconnectNuki();

        assertThat(mvc.get().uri("/settings/nuki").with(SignedIn.owner(ALICE)))
                .bodyText().contains("Nuki ha rechazado", "Volver a conectar con Nuki", "/settings/nuki/oauth");
    }

    /** A pasted API token was the other way to connect until 2.18.0. */
    @Test
    void thereIsNoWayLeftToPasteAToken() {
        assertThat(mvc.put().uri("/settings/nuki").with(SignedIn.owner(ALICE)).with(csrf()).param("apiToken", TOKEN))
                .hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
        nuki.verify(0, anyRequestedFor(anyUrl()));
        assertNoConnection();
    }

    @Test
    void pageConfirmsTheDisconnection() {
        assertThat(mvc.get().uri("/settings/nuki").with(SignedIn.owner(ALICE)).flashAttr("disconnected", true))
                .bodyText().contains("Nuki desconectado: hemos borrado los tokens", "Todavía no has conectado");
    }

    @Test
    void pageNamesTheLocksJustFound() {
        assertThat(mvc.get().uri("/settings/nuki").with(SignedIn.owner(ALICE)).flashAttr("connectedLocks", List.of("Casa")))
                .bodyText().contains("Tus cerraduras ya están aquí", "Casa", "href=\"/\"", "<details id=\"nuki-web\">");
    }

    @Test
    void guideToActivatingNukiWebIsFoldedAway() {
        assertThat(mvc.get().uri("/settings/nuki").with(SignedIn.owner(ALICE)))
                .bodyText()
                .containsPattern("<details id=\"nuki-web\">\\s*"
                        + "<summary>¿Acabas de instalar tu Nuki\\?</summary>")
                .contains("<strong>Características y configuración</strong>",
                        "href=\"https://help.nuki.io/hc/es/articles/4402921104145\"",
                        "href=\"https://help.nuki.io/hc/es/articles/360016485718\"",
                        "<a href=\"/\">Cerraduras</a>");
    }

    @Test
    void guideUnfoldsWhenConnectingFoundNoLocks() {
        assertThat(mvc.get().uri("/settings/nuki").with(SignedIn.owner(ALICE)).flashAttr("connectedLocks", List.of()))
                .bodyText().contains("no hemos encontrado ninguna cerradura en tu cuenta: actívalas en Nuki Web",
                        "<details id=\"nuki-web\" open=\"open\">");
    }

    @Test
    void ownerDoesNotSeeAnotherOwnersConnection() {
        nukiAccepts(TOKEN);
        connect("bob@example.com", TOKEN);

        assertThat(mvc.get().uri("/settings/nuki").with(SignedIn.owner(ALICE))).bodyText().contains("Todavía no has conectado");
    }

    @Test
    void signedInUserWithoutOwnerIsForbidden() {
        assertThat(mvc.get().uri("/settings/nuki").with(SignedIn.owner("deleted@example.com"))).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void panelAsksAnOwnerWithoutConnectionToConnectNuki() {
        assertThat(mvc.get().uri("/").with(SignedIn.owner(ALICE))).bodyText().contains("Conecta tu cuenta de Nuki");
    }

    @Test
    void panelWarnsWhenNukiRejectedTheToken() {
        nukiAccepts(TOKEN);
        connect(ALICE, TOKEN);
        assertThat(mvc.get().uri("/").with(SignedIn.owner(ALICE)))
                .hasStatusOk().bodyText().doesNotContain("Conecta tu cuenta de Nuki", "Nuki ha rechazado");

        data.disconnectNuki();

        assertThat(mvc.get().uri("/").with(SignedIn.owner(ALICE))).bodyText().contains("Nuki ha rechazado la conexión");
    }

    @Test
    void disconnectingDeletesTheTokenButKeepsLocksAndLinks() {
        nukiAccepts(TOKEN);
        connect(ALICE, TOKEN);
        var lockId = migrator.sql("SELECT id FROM lock WHERE nuki_smartlock_id = 17").query(UUID.class).single();
        data.link(lockId, "Invitado", "now()", TestData.IN_A_DAY);
        var linkBefore = link();

        var result = mvc.delete().uri("/settings/nuki").with(SignedIn.owner(ALICE)).with(csrf()).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/settings/nuki");
        assertNoConnection();
        assertThat(link()).isEqualTo(linkBefore);
        nuki.resetRequests();
        assertThatThrownBy(() -> locks.unlatch(aliceId, 17))
                .isInstanceOfSatisfying(LockProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo(LockProviderException.Reason.NOT_CONNECTED));
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    /** It stops every link of the owner's: not one tap. */
    @Test
    void disconnectingAsksFirstWhatItStops() {
        nukiAccepts(TOKEN);
        connect(ALICE, TOKEN);

        assertThat(mvc.get().uri("/settings/nuki").with(SignedIn.owner(ALICE))).bodyText().contains(
                "data-confirm=\"Tus enlaces dejarán de abrir la puerta hasta que vuelvas a conectar Nuki. "
                        + "¿Desconectar?\"", "/js/confirm.js");
    }

    @Test
    void htmlFormSendsTheDeleteAsAPostWithHiddenMethod() {
        nukiAccepts(TOKEN);
        connect(ALICE, TOKEN);

        assertThat(mvc.post().uri("/settings/nuki").with(SignedIn.owner(ALICE)).with(csrf()).param("_method", "delete"))
                .hasStatus(HttpStatus.FOUND);
        assertNoConnection();
    }

    @Test
    void disconnectingTouchesOnlyTheOwnersOwnConnection() {
        nukiAccepts(TOKEN);
        connect(ALICE, TOKEN);
        connect("bob@example.com", TOKEN);

        mvc.delete().uri("/settings/nuki").with(SignedIn.owner(ALICE)).with(csrf()).exchange();

        assertThat(migrator.sql("SELECT o.email FROM nuki_connection c JOIN owner o ON o.id = c.owner_id")
                .query(String.class).list()).containsExactly("bob@example.com");
    }

    @Test
    void disconnectingRequiresTheCsrfToken() {
        nukiAccepts(TOKEN);
        connect(ALICE, TOKEN);

        assertThat(mvc.delete().uri("/settings/nuki").with(SignedIn.owner(ALICE))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(storedToken(aliceId)).isNotNull();
    }

    /** Everything that decides a link's state and its URL. */
    private String link() {
        return migrator.sql("SELECT concat_ws('|', lock_id, token_version, valid_from, valid_until, revoked_at) FROM access_link")
                .query(String.class)
                .single();
    }

    private void connect(String email, String accessToken) {
        NukiStandInConfiguration.connect(mvc, nuki, email, accessToken);
    }

    private void nukiAccepts(String token) {
        nuki.stubFor(get("/smartlock").withHeader("Authorization", equalTo("Bearer " + token))
                .willReturn(okJson("[{\"smartlockId\": 17, \"type\": 5, \"name\": \"Casa\", \"serverState\": 0}]")));
        nuki.stubFor(get(urlPathEqualTo("/smartlock/log")).withHeader("Authorization", equalTo("Bearer " + token))
                .withQueryParam("limit", equalTo("1"))
                .willReturn(okJson("[]")));
    }

    private byte[] storedToken(UUID ownerId) {
        return migrator.sql("SELECT access_token_encrypted FROM nuki_connection WHERE owner_id = ?")
                .param(ownerId)
                .query(byte[].class)
                .single();
    }

    private void assertNoConnection() {
        assertThat(migrator.sql("SELECT count(*) FROM nuki_connection").query(Long.class).single()).isZero();
    }

    private UUID insertOwner(String email) {
        return data.owner(email);
    }
}
