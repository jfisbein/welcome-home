package welcomehome.nuki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.crypto.CryptoService;

/**
 * Without an OAuth2 client for Nuki, pasting an API token is the only way to connect, as before there was one.
 * Same configuration as the web security tests, so they share one application context.
 */
@IntegrationTest
@AutoConfigureMockMvc
class NukiConnectionWithoutAuthorizationTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    CryptoService crypto;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID aliceId;

    @BeforeEach
    void insertAlice() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        aliceId = migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id").param(ALICE)
                .query(UUID.class).single();
    }

    @Test
    void neitherAuthorizingNorItsCallbackExists() {
        assertThat(mvc.get().uri("/settings/nuki/oauth").with(owner())).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/nuki/callback").with(owner()).param("code", "c").param("state", "s"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void howToGetATokenIsShownOpenUntilTheOwnerConnects() {
        assertThat(mvc.get().uri("/settings/nuki").with(owner()))
                .bodyText().contains("<details open", "Cómo conseguir el API token",
                        "<a href=\"https://web.nuki.io\" target=\"_blank\" rel=\"noopener noreferrer\">",
                        "Generar token de API", "Ver dispositivos", "Gestionar dispositivos",
                        "Ver protocolos de cierre y obtener notificaciones de protocolo", "name=\"apiToken\"")
                .doesNotContain("/settings/nuki/oauth", "Usar un API token en su lugar", "Te llevamos a Nuki");

        migrator.sql("INSERT INTO nuki_connection (owner_id, access_token_encrypted, status) VALUES (?, ?, 'OK')")
                .params(aliceId, crypto.encryptAccessToken(aliceId, "alices-token")).update();

        assertThat(mvc.get().uri("/settings/nuki").with(owner()))
                .bodyText().contains("Conectado con un API token.", "Cómo conseguir el API token", "Cambiar API token")
                .doesNotContain("<details open", "/settings/nuki/oauth", "Usar tu cuenta de Nuki");
    }

    private static RequestPostProcessor owner() {
        return user(ALICE).roles("OWNER");
    }
}
