package welcomehome.nuki;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

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
            registry.add("welcomehome.nuki.initial-backoff", () -> "PT0.01S");
            // Files end with a newline, which the client id and secret do not.
            registry.add("welcomehome.nuki.oauth.client-id", () -> CLIENT_ID + "\n");
            registry.add("welcomehome.nuki.oauth.client-secret", () -> CLIENT_SECRET + "\n");
            // Confirming an opening reads the stand-in's log: no need to wait as for a real lock.
            registry.add("welcomehome.access.confirmation.interval", () -> "PT0.1S");
            registry.add("welcomehome.access.confirmation.timeout", () -> "PT1S");
        };
    }

    /**
     * Signs a webhook's body as Nuki does, with the client's secret: its HMAC-SHA256 in lowercase hexadecimal, for
     * the X-Nuki-Signature-SHA256 header (https://docs.nuki.io/guide/webhooks/monitoring).
     *
     * @param body the webhook's body, exactly as sent
     * @return the signature
     */
    public static String signature(String body) {
        return hmacSha256(CLIENT_SECRET, body);
    }

    /**
     * The HMAC-SHA256 of a text with a key, in lowercase hexadecimal.
     *
     * @param key  the key, as UTF-8
     * @param text the text, as UTF-8
     * @return the HMAC
     */
    public static String hmacSha256(String key, String text) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
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
