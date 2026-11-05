package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import welcomehome.IntegrationTest;

/**
 * A form from a page whose session ended, as after a restart, which loses every session, or 30 minutes without use.
 * Against a running server, as only a real one tells a session it no longer has from none at all; same
 * configuration as SessionCookieTests, so they share one application context.
 */
@IntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class EndedSessionTests {

    /** The cookie of a session the server no longer has. */
    static final String ENDED_SESSION = "JSESSIONID=0123456789ABCDEF0123456789ABCDEF";
    static final String FORM = "application/x-www-form-urlencoded";

    @LocalServerPort
    int port;

    final HttpClient client = HttpClient.newHttpClient();

    @Test
    void signingOutFromAPageWhoseSessionEndedLandsWhereSigningOutDoes() throws Exception {
        var response = post("/logout", ENDED_SESSION, FORM, "_csrf=from-the-ended-session");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).endsWith("/login?logout"));
    }

    @Test
    void anyOtherFormFromAPageWhoseSessionEndedLeadsToSigningInAgain() throws Exception {
        var response = post("/locks/" + UUID.randomUUID() + "/links", ENDED_SESSION, FORM,
                "_csrf=from-the-ended-session&label=Ana&validFrom=2030-11-01T15:00&validUntil=2030-11-03T11:00");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).endsWith("/login?expired"));
        assertThat(get("/login?expired")).contains("Tu sesión había caducado, así que no se ha guardado nada.");
    }

    /** Only a session that ended explains a bad token; a script reads the 403 itself. */
    @Test
    void aBadTokenIsStillRefusedWithoutAnEndedSessionOrFromAScript() throws Exception {
        assertThat(post("/logout", null, FORM, "_csrf=forged").statusCode()).as("no session").isEqualTo(403);
        assertThat(post("/logout", liveSession(), FORM, "_csrf=forged").statusCode()).as("a live session")
                .isEqualTo(403);
        assertThat(post("/webauthn/authenticate/options", ENDED_SESSION, "application/json", "{}").statusCode())
                .as("a script").isEqualTo(403);
    }

    /** The cookie of a session the server has: the login page starts one for its CSRF token. */
    private String liveSession() throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/login")).build(), HttpResponse.BodyHandlers.discarding());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> post(String path, @Nullable String cookie, String contentType, String body)
            throws Exception {
        var request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
