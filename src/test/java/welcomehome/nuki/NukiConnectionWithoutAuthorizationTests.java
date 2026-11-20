package welcomehome.nuki;

import static org.assertj.core.api.Assertions.assertThat;

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
import welcomehome.SignedIn;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
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
    TestData data;
    UUID aliceId;

    @BeforeEach
    void insertAlice() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        data.deleteOwners();
        aliceId = data.owner(ALICE);
    }

    @Test
    void neitherAuthorizingNorItsCallbackExists() {
        assertThat(mvc.get().uri("/settings/nuki/oauth").with(owner())).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/nuki/callback").with(owner()).param("code", "c").param("state", "s"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    /** An install whose owner has not set welcome-home's client at Nuki: the page says so, and offers no way in. */
    @Test
    void thePageSaysThatThisInstallCannotConnectNukiYet() {
        assertThat(mvc.get().uri("/settings/nuki").with(owner()))
                .bodyText().contains("Todavía no has conectado",
                        "Esta instalación de welcome-home no tiene configurado su acceso a Nuki")
                .doesNotContain("/settings/nuki/oauth", "Te llevamos a Nuki", "API token");
    }

    private static RequestPostProcessor owner() {
        return SignedIn.owner(ALICE);
    }
}
