package welcomehome;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * What a visitor who is not signed in gets for an error, from a running server: MockMvc does not forward errors
 * to /error. Same configuration as the session cookie test, so they share one application context.
 */
@IntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ErrorPagesTests {

    @LocalServerPort
    int port;

    @Test
    void anErrorOnAPublicRouteIsTheAppsPageWithItsStatusAndNoSession() throws Exception {
        var notFound = get("/css/nope.css", "text/html", "es");
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(notFound.body()).contains("<html lang=\"es\">", "<title>Esta página no existe · welcome-home</title>",
                        "Error 404")
                .as("no menu for whoever is not signed in").doesNotContain("href=\"/locks\"", "_csrf");
        assertThat(notFound.headers().allValues("Set-Cookie")).as("no session for a guest").isEmpty();

        var notAllowed = get("/api/open", "text/html", "en");
        assertThat(notAllowed.statusCode()).isEqualTo(405);
        assertThat(notAllowed.body()).contains("<html lang=\"en\">", "The request could not be handled", "Error 405");
        assertThat(notAllowed.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void scriptsThatAskForJsonStillGetJson() throws Exception {
        var response = get("/api/open", "application/json", "es");

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.body()).startsWith("{").contains("\"status\":405");
    }

    @Test
    void anUnknownAddressStillAsksToSignInAndKeepsTheSessionOutOfTheUrl() throws Exception {
        var response = get("/no-such-page", "text/html", "es");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(location ->
                assertThat(location).endsWith("/login").doesNotContain("jsessionid"));
    }

    private HttpResponse<String> get(String path, String accept, String language) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .header("Accept", accept).header("Accept-Language", language).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
