package welcomehome.access;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The country the guest's request came from, for the history. Cloudflare sets CF-IPCountry on every request,
 * with its IP Geolocation on: an ISO 3166 code, T1 for Tor, or XX when it does not know, which is not kept.
 */
@Component
class ClientCountry {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9]");
    private static final String UNKNOWN = "XX";

    private final @Nullable String header;

    ClientCountry(AccessProperties properties) {
        var configured = properties.clientCountryHeader();
        this.header = configured == null || configured.isBlank() ? null : configured.strip();
    }

    /** @return the country's code, or empty if no country is known */
    Optional<String> of(HttpServletRequest request) {
        var value = header == null ? null : request.getHeader(header);
        return Optional.ofNullable(value)
                .map(code -> code.strip().toUpperCase(Locale.ROOT))
                .filter(code -> CODE.matcher(code).matches() && !code.equals(UNKNOWN));
    }
}
