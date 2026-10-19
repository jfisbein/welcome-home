package welcomehome;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Emails are optional: an SMTP server that cannot be reached must not make the app unhealthy, as the stack's
 * tunnel starts only once the app is healthy. Port 1 on this host refuses connections.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.mail.host=127.0.0.1", "spring.mail.port=1"})
class HealthWithoutMailServerTests {

    @Autowired
    MockMvcTester mvc;

    @Test
    void theAppIsHealthyWhileTheMailServerCannotBeReached() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
    }
}
