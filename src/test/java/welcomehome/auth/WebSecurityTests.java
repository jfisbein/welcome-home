package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;

@IntegrationTest
@AutoConfigureMockMvc
class WebSecurityTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void anonymousVisitorIsSentToLogin() {
        assertThat(mvc.get().uri("/"))
                .hasStatus(HttpStatus.FOUND)
                .redirectedUrl().endsWith("/login");
    }

    @Test
    void loginPageIsPublicAndInSpanish() {
        assertThat(mvc.get().uri("/login"))
                .hasStatusOk()
                .bodyText().contains("Entrar con passkey");
    }

    @Test
    void stylesAndScriptsArePublic() {
        assertThat(mvc.get().uri("/css/pico.min.css")).hasStatusOk();
        assertThat(mvc.get().uri("/js/webauthn.js")).hasStatusOk();
        assertThat(mvc.get().uri("/js/login.js")).hasStatusOk();
    }

    @Test
    void signedInOwnerSeesThePanel() {
        TestDatabaseConfiguration.asMigrator(postgres)
                .sql("INSERT INTO owner (email) VALUES ('alice@example.com') ON CONFLICT DO NOTHING")
                .update();

        assertThat(mvc.get().uri("/").with(user("alice@example.com").roles(OwnerUserDetailsService.OWNER)))
                .hasStatusOk()
                .bodyText().contains("alice@example.com");
    }

    @Test
    void passkeyAuthenticationOptionsUseThePublicHostAsRelyingParty() {
        assertThat(mvc.post().uri("/webauthn/authenticate/options").with(csrf()))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.rpId").isEqualTo("welcome.example");
                    json.assertThat().extractingPath("$.challenge").asString().isNotBlank();
                });
    }

    @Test
    void passkeyEndpointsRequireTheCsrfToken() {
        assertThat(mvc.post().uri("/webauthn/authenticate/options")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void logoutReturnsToLogin() {
        assertThat(mvc.post().uri("/logout").with(user("alice@example.com")).with(csrf()))
                .hasStatus(HttpStatus.FOUND)
                .redirectedUrl().isEqualTo("/login?logout");
    }
}
