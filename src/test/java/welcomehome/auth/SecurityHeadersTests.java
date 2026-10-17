package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import welcomehome.IntegrationTest;

@IntegrationTest
@AutoConfigureMockMvc
class SecurityHeadersTests {

    @Autowired
    MockMvcTester mvc;

    @Test
    void pagesAllowOnlyTheAppsOwnScriptsAndStyles() {
        for (var page : new String[] {"/login", "/invite/unknown"}) {
            assertThat(mvc.get().uri(page))
                    .as(page)
                    .headers()
                    .hasValue("Content-Security-Policy", SecurityConfiguration.CONTENT_SECURITY_POLICY);
        }
        assertThat(SecurityConfiguration.CONTENT_SECURITY_POLICY)
                .contains("default-src 'self'", "frame-ancestors 'none'")
                .doesNotContain("unsafe-inline", "unsafe-eval");
    }

    @Test
    void noPageSendsAReferrer() {
        assertThat(mvc.get().uri("/invite/unknown")).headers().hasValue("Referrer-Policy", "no-referrer");
        assertThat(mvc.get().uri("/").with(user("alice@example.com")))
                .headers().hasValue("Referrer-Policy", "no-referrer");
    }

    @Test
    void pagesCannotBeFramedOrSniffed() {
        assertThat(mvc.get().uri("/login"))
                .headers()
                .hasValue("X-Frame-Options", "DENY")
                .hasValue("X-Content-Type-Options", "nosniff");
    }

    @Test
    void httpsBehindTheTunnelGetsHsts() {
        assertThat(mvc.get().uri("/login").header("X-Forwarded-Proto", "https"))
                .headers()
                .hasValue("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains");
    }

    @Test
    void plainHttpGetsNoHsts() {
        assertThat(mvc.get().uri("/login")).headers().doesNotContainHeader("Strict-Transport-Security");
    }

    @Test
    void postsWithoutTheCsrfTokenAreRejected() {
        assertThat(mvc.post().uri("/invite/unknown/options")
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"alice@example.com\"}"))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.post().uri("/login/webauthn").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.post().uri("/logout").with(user("alice@example.com"))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.post().uri("/invite/unknown/options").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"alice@example.com\"}"))
                .as("the same request with the token")
                .hasStatus(HttpStatus.GONE);
    }
}
