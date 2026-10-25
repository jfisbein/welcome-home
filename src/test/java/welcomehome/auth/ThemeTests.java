package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;

/**
 * The color theme: the system's unless chosen in Configuración → Preferencias, kept in a cookie and drawn on
 * every page. Same configuration as the language tests, so they share one application context.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ThemeTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PostgreSQLContainer postgres;

    @BeforeEach
    void insertAlice() {
        var migrator = TestDatabaseConfiguration.asMigrator(postgres);
        for (var table : new String[] {"user_credentials", "user_entities", "invitation", "owner"}) {
            migrator.sql("DELETE FROM " + table).update();
        }
        migrator.sql("INSERT INTO owner (email) VALUES (?)").param(ALICE).update();
    }

    @Test
    void withoutAChoicePagesFollowTheSystem() {
        assertThat(body(mvc.get().uri("/login").exchange())).contains("<html lang=\"es\">").doesNotContain("data-theme");
        assertThat(body(mvc.get().uri("/settings/preferences").with(owner()).exchange()))
                .doesNotContain("data-theme")
                .containsPattern("value=\"auto\"\\s+checked=\"checked\"");
    }

    @Test
    void everyPageIsDrawnInTheChosenTheme() {
        var dark = new Cookie(Theme.COOKIE, "dark");
        for (var page : new String[] {"/login", "/o/nonsense", "/invite/nonsense"}) {
            assertThat(body(mvc.get().uri(page).cookie(dark).exchange())).as(page).contains("data-theme=\"dark\"");
        }
        assertThat(body(mvc.get().uri("/").with(owner()).cookie(dark).exchange())).contains("data-theme=\"dark\"");
        assertThat(body(mvc.get().uri("/settings/preferences").with(owner()).cookie(new Cookie(Theme.COOKIE, "light"))
                .exchange()))
                .contains("data-theme=\"light\"")
                .containsPattern("value=\"light\"\\s+checked=\"checked\"");
    }

    @Test
    void aCookieWithAnotherValueIsIgnored() {
        assertThat(body(mvc.get().uri("/login").cookie(new Cookie(Theme.COOKIE, "\"><script>")).exchange()))
                .doesNotContain("data-theme", "<script>");
    }

    @Test
    void choosingLightOrDarkKeepsItInACookieForAYear() {
        var result = chooseTheme("dark");

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/settings/preferences");
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .startsWith("theme=dark;")
                .contains("Path=/", "Max-Age=31536000", "HttpOnly", "SameSite=Lax", "Secure");
        assertThat(result).flash().containsEntry("themeSaved", true);
    }

    @Test
    void choosingAutomaticDeletesTheCookie() {
        assertThat(chooseTheme("auto").getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .startsWith("theme=;").contains("Max-Age=0", "Path=/");
    }

    @Test
    void onlyTheOfferedThemesCanBeChosen() {
        assertThat(chooseTheme("sepia")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.put().uri("/settings/theme").with(owner()).param("theme", "dark"))
                .as("without the CSRF token").hasStatus(HttpStatus.FORBIDDEN);
    }

    private MvcTestResult chooseTheme(String theme) {
        return mvc.put().uri("/settings/theme").with(owner()).with(csrf()).param("theme", theme).exchange();
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static RequestPostProcessor owner() {
        return user(ALICE).roles("OWNER");
    }
}
