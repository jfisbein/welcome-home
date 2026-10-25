package welcomehome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.nuki.NukiStandInConfiguration;

/**
 * The error page as a signed-in owner sees it, with the menu, in their language. MockMvc does not forward
 * errors, so the test asks for /error as the servlet container would. Same configuration as the locks tests, so
 * they share one application context.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class SignedInErrorPageTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PostgreSQLContainer postgres;

    @BeforeEach
    void insertAlice() {
        var migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        migrator.sql("INSERT INTO owner (email, language) VALUES (?, 'en')").param(ALICE).update();
    }

    @Test
    void aSignedInOwnerGetsThePageWithTheMenuInTheirLanguage() {
        var result = error(404, "/locks/00000000-0000-0000-0000-000000000000");

        assertThat(result).hasStatus(404);
        assertThat(body(result)).contains("<html lang=\"en\">", "This page does not exist", "Error 404",
                "href=\"/locks\"", "href=\"/settings\"", "Go to the home page");
    }

    @Test
    void eachKindOfErrorSaysItsOwnThing() {
        assertThat(body(error(403, "/admin/owners"))).contains("You cannot see this page", "Error 403");
        assertThat(body(error(400, "/locks/x/history"))).contains("The request could not be handled", "Error 400");
        assertThat(body(error(500, "/"))).contains("Something went wrong", "Error 500");
    }

    private MvcTestResult error(int status, String uri) {
        return mvc.get().uri("/error").accept(MediaType.TEXT_HTML).with(user(ALICE).roles("OWNER"))
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, uri)
                .exchange();
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
