package welcomehome.config;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The country a request came from, a guest's for the history or an owner's for their account's activity. Cloudflare sets CF-IPCountry on every request,
 * with its IP Geolocation on: an ISO 3166 code, T1 for Tor, or XX when it does not know, which is not kept.
 */
@Component
public class ClientCountry {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9]");
    private static final String UNKNOWN = "XX";

    private final @Nullable String header;

    ClientCountry(ClientProperties properties) {
        var configured = properties.clientCountryHeader();
        this.header = configured == null || configured.isBlank() ? null : configured.strip();
    }

    /**
     * The country a request came from, as the proxy says.
     *
     * @param request the request
     * @return the country's code, or empty if no country is known
     */
    public Optional<String> of(HttpServletRequest request) {
        var value = header == null ? null : request.getHeader(header);
        return Optional.ofNullable(value)
                .map(code -> code.strip().toUpperCase(Locale.ROOT))
                .filter(code -> CODE.matcher(code).matches() && !code.equals(UNKNOWN));
    }
}
