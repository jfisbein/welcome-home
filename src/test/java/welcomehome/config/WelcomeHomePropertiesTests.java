package welcomehome.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WelcomeHomePropertiesTests {

    @Test
    void theAppDoesNotStartWithoutABaseUrl() {
        assertThatThrownBy(() -> new WelcomeHomeProperties(null)).hasMessage("No base URL configured: set BASE_URL");
        assertThatThrownBy(() -> new WelcomeHomeProperties(URI.create("")))
                .hasMessage("No base URL configured: set BASE_URL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"welcome.example", "ftp://welcome.example", "https://welcome.example/app",
            "https://welcome.example/?x=1", "https://welcome.example/#top", "https:///path"})
    void theBaseUrlIsAnHttpUrlWithAHostAndNothingAfterIt(String url) {
        assertThatThrownBy(() -> new WelcomeHomeProperties(URI.create(url)))
                .hasMessageStartingWith("BASE_URL must be an http or https URL");
    }

    @Test
    void linksOriginAndHostComeFromTheBaseUrlWithoutItsTrailingSlash() {
        var app = new WelcomeHomeProperties(URI.create("https://welcome.example:8443/"));

        assertThat(app.link("/o/abc")).isEqualTo(URI.create("https://welcome.example:8443/o/abc"));
        assertThat(app.origin()).isEqualTo("https://welcome.example:8443");
        assertThat(app.host()).isEqualTo("welcome.example");
    }
}
