package welcomehome.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import welcomehome.IntegrationTest;
import welcomehome.crypto.LinkTokenSigner;
import welcomehome.nuki.NukiStandInConfiguration;

/**
 * A token in a log opens the door as well as the link does. Spring logs request paths and bodies at DEBUG,
 * so the tests turn DEBUG on, as someone looking into a problem might.
 * Same configuration as the locks tests, so they share one application context.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class TokenLoggingTests {

    static final String SPRING = "org.springframework";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    LinkTokenSigner signer;

    @Autowired
    LoggingSystem loggingSystem;

    String token;

    @BeforeEach
    void logAtDebug() {
        token = signer.sign(UUID.randomUUID(), 1);
        loggingSystem.setLogLevel(SPRING, LogLevel.DEBUG);
    }

    @AfterEach
    void logAsBefore() {
        loggingSystem.setLogLevel(SPRING, null);
    }

    @Test
    void guestPageLogsNoToken(CapturedOutput output) {
        mvc.get().uri("/o/" + token).exchange();

        assertThat(output.getAll()).contains("/o/***").doesNotContain(token).doesNotContain(mac());
    }

    @Test
    void openingLogsNoToken(CapturedOutput output) {
        mvc.post().uri("/api/open").with(csrf())
                .with(request -> {
                    request.setRemoteAddr("10.252.0.1");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + token + "\"}")
                .exchange();

        assertThat(output.getAll()).contains("OpenRequest[token=***, door=null]").doesNotContain(mac());
    }

    @Test
    void nukisWayBackLogsNoCode(CapturedOutput output) {
        mvc.get().uri("/nuki/callback?code=the-authorization-code&state=the-state")
                .with(user("alice@example.com").roles("OWNER")).exchange();

        assertThat(output.getAll()).contains("/nuki/callback?***")
                .doesNotContain("the-authorization-code").doesNotContain("the-state");
    }

    /** The token's secret part: the link id before the dot is no secret. */
    private String mac() {
        return token.substring(token.indexOf('.') + 1);
    }
}
