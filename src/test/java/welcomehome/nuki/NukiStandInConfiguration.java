package welcomehome.nuki;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;



import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.util.UriComponentsBuilder;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import welcomehome.SignedIn;

/**
 * A stand-in for the Nuki Web API, for integration tests: the application calls it instead of Nuki.
 * Test classes that import it with the same other annotations share one application context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class NukiStandInConfiguration {

    /** welcome-home's OAuth2 client at the stand-in. */
    public static final String CLIENT_ID = "welcome-home-client";

    /** Its secret. */
    public static final String CLIENT_SECRET = "welcome-home-secret";

    @Bean(destroyMethod = "stop")
    WireMockServer nukiStandIn() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @Bean
    DynamicPropertyRegistrar nukiProperties(WireMockServer nukiStandIn) {
        return registry -> {
            registry.add("welcomehome.nuki.base-url", nukiStandIn::baseUrl);
            registry.add("welcomehome.nuki.revocation-url", () -> nukiStandIn.baseUrl() + "/oauth2/revoke");
            registry.add("welcomehome.nuki.initial-backoff", () -> "PT0.01S");
            // Files end with a newline, which the client id and secret do not.
            registry.add("welcomehome.nuki.oauth.client-id", () -> CLIENT_ID + "\n");
            registry.add("welcomehome.nuki.oauth.client-secret", () -> CLIENT_SECRET + "\n");
            // Confirming an opening reads the stand-in's log: no need to wait as for a real lock.
            registry.add("welcomehome.access.confirmation.interval", () -> "PT0.1S");
            registry.add("welcomehome.access.confirmation.timeout", () -> "PT1S");
        };
    }

    /** What welcome-home asks Nuki for, as Nuki's token answer lists what it granted. */
    public static final String SCOPE = "smartlock.readOnly smartlock.action smartlock.log offline_access";

    /**
     * Nuki exchanges a code for tokens: the access token named, which does not expire for a day, so that no test
     * that is not about refreshing meets a refresh.
     *
     * @param nuki        the stand-in
     * @param code        the code Nuki sent the owner back with
     * @param accessToken the access token it is exchanged for
     */
    public static void nukiGives(WireMockServer nuki, String code, String accessToken) {
        nuki.stubFor(post("/oauth/token").withFormParam("code", equalTo(code)).willReturn(okJson("""
                {"access_token": "%s", "token_type": "bearer", "expires_in": 86400,
                 "refresh_token": "refresh-of-%s", "scope": "%s"}
                """.formatted(accessToken, accessToken, SCOPE))));
    }

    /**
     * Connects an owner as they do: they are sent to Nuki to authorize welcome-home, and come back with a code that
     * Nuki exchanges for the access token named. What Nuki answers to that token, its locks and that its log can be
     * read, is the caller's to stub first.
     *
     * @param mvc         the app
     * @param nuki        the stand-in
     * @param email       the owner
     * @param accessToken the access token Nuki gives them
     */
    public static void connect(MockMvcTester mvc, WireMockServer nuki, String email, String accessToken) {
        var code = "code-" + UUID.randomUUID();
        nukiGives(nuki, code, accessToken);
        var owner = SignedIn.owner(email);
        var session = new MockHttpSession();
        var toNuki = mvc.get().uri("/settings/nuki/oauth").with(owner).session(session).exchange();
        assertThat(toNuki).hasStatus(HttpStatus.FOUND);
        var state = UriComponentsBuilder.fromUriString(
                        Objects.requireNonNull(toNuki.getMvcResult().getResponse().getRedirectedUrl()))
                .build().getQueryParams().getFirst("state");
        var back = mvc.get().uri("/nuki/callback?code=" + code + "&state=" + state).with(owner).session(session)
                .exchange();
        assertThat(back).hasStatus(HttpStatus.FOUND);
        assertThat(back.getMvcResult().getFlashMap().get("authorizationError")).as("connecting Nuki").isNull();
    }

    /**
     * Lets any token read the account's log, which connecting checks.
     *
     * @param nuki the stand-in
     */
    public static void logIsReadable(WireMockServer nuki) {
        nuki.stubFor(get(urlPathEqualTo("/smartlock/log")).willReturn(okJson("[]")));
    }
}
