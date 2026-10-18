package welcomehome.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;

/** Without an SMTP server, as in development: the page says so and nothing is sent. */
@IntegrationTest
@AutoConfigureMockMvc
class NotificationsWithoutEmailTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PostgreSQLContainer postgres;

    @BeforeEach
    void insertAlice() {
        var migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        migrator.sql("INSERT INTO owner (email) VALUES (?)").param(ALICE).update();
    }

    @Test
    void thePageSaysThatNoEmailWillArrive() {
        assertThat(mvc.get().uri("/notifications").with(owner())).hasStatusOk().bodyText()
                .contains("El envío de emails no está configurado", "href=\"/notifications\"")
                .doesNotContain("Enviarme un email de prueba");
    }

    @Test
    void aTestEmailFailsPolitely() {
        var result = mvc.post().uri("/notifications/test").with(owner()).with(csrf()).exchange();

        assertThat(result).hasStatus3xxRedirection();
        assertThat(result.getMvcResult().getFlashMap().get("testFailed")).isEqualTo(true);
    }

    private static RequestPostProcessor owner() {
        return user(ALICE).roles("OWNER");
    }
}
