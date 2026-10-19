package welcomehome.config;

import java.net.URI;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app's public URL, {@code welcomehome.base-url} ({@code BASE_URL}): the start of every link it builds, in
 * invitations, guest URLs and emails, and the passkeys' relying party. Checked once, when the app starts.
 *
 * @param baseUrl the public URL, such as {@code https://welcome.example}: http or https, with a host and no path,
 *                query or fragment; a trailing slash is dropped
 */
@ConfigurationProperties("welcomehome")
public record WelcomeHomeProperties(URI baseUrl) {

    private static final Set<String> SCHEMES = Set.of("http", "https");

    /**
     * Checks the URL and drops a trailing slash.
     *
     * @param baseUrl the public URL, which the app does not start without
     */
    public WelcomeHomeProperties(@Nullable URI baseUrl) {
        if (baseUrl == null || baseUrl.toString().isBlank()) {
            throw new IllegalStateException("No base URL configured: set BASE_URL");
        }
        var path = baseUrl.getRawPath();
        var scheme = baseUrl.getScheme();
        if (scheme == null || !SCHEMES.contains(scheme) || baseUrl.getHost() == null
                || (path != null && !path.isEmpty() && !path.equals("/"))
                || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null) {
            throw new IllegalStateException("BASE_URL must be an http or https URL with a host and nothing after it, "
                    + "such as https://welcome.example, not " + baseUrl);
        }
        this.baseUrl = URI.create(baseUrl.getScheme() + "://" + baseUrl.getRawAuthority());
    }

    /**
     * A URL of the app.
     *
     * @param path the URL's path, starting with a slash, and its query if any
     * @return the URL, under the public URL
     */
    public URI link(String path) {
        return URI.create(baseUrl + path);
    }

    /**
     * The public URL's origin, which browsers send with passkey ceremonies.
     *
     * @return the scheme, host and port, such as {@code https://welcome.example}
     */
    public String origin() {
        return baseUrl.toString();
    }

    /**
     * The public URL's host, the passkeys' relying party: passkeys created for one host do not work on another.
     *
     * @return the host
     */
    public String host() {
        var host = baseUrl.getHost();
        return host == null ? "" : host;
    }
}
