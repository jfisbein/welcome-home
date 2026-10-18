package welcomehome;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.WebApplicationContext;

/** The one-off commands start the application without the web server: it must start that way too. */
@IntegrationTest(webEnvironment = WebEnvironment.NONE)
class CommandLineTests {

    @Autowired
    ApplicationContext context;

    @Test
    void theApplicationStartsWithoutTheWebServer() {
        assertThat(context).isNotInstanceOf(WebApplicationContext.class);
        assertThat(context.getBeanNamesForType(welcomehome.admin.InvitationService.class)).hasSize(1);
    }

    @Test
    void onlyTheCommandsRunWithoutTheWebServer() {
        assertThat(WelcomeHomeApplication.runsACommand("--bootstrap-admin")).isTrue();
        assertThat(WelcomeHomeApplication.runsACommand("--recover-owner=ana@example.com")).isTrue();
        assertThat(WelcomeHomeApplication.runsACommand()).isFalse();
        assertThat(WelcomeHomeApplication.runsACommand("--server.port=9090")).isFalse();
    }
}
