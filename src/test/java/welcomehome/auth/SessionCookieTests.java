package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionTrackingMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import welcomehome.IntegrationTest;

/** MockMvc has no real session cookie, so this test asks a running server. */
@IntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SessionCookieTests {

    @LocalServerPort
    int port;

    @Autowired
    ServletContext servletContext;

    @Test
    void theSessionTravelsOnlyInItsCookieNeverInAUrl() {
        assertThat(servletContext.getEffectiveSessionTrackingModes()).containsExactly(SessionTrackingMode.COOKIE);
    }

    @Test
    void sessionCookieIsSecureHttpOnlyAndSameSite() throws Exception {
        // The login page puts the CSRF token in the session, so it starts one.
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login")).build(),
                    HttpResponse.BodyHandlers.discarding());

            assertThat(response.headers().allValues("Set-Cookie"))
                    .singleElement().asString()
                    .startsWith("JSESSIONID=")
                    .contains("; Secure", "; HttpOnly", "; SameSite=Lax");
        }
    }
}
