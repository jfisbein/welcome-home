package welcomehome.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where a request comes from, as the proxy in front of the app says. Under {@code welcomehome.access}, where they
 * were when only the guest flow used them.
 *
 * @param clientIpHeader      the header that holds the client's IP address; blank to use the address the request
 *                            came from
 * @param clientCountryHeader the header that holds the client's country; blank to record none
 */
@ConfigurationProperties("welcomehome.access")
public record ClientProperties(@DefaultValue("CF-Connecting-IP") @Nullable String clientIpHeader,
                               @DefaultValue("CF-IPCountry") @Nullable String clientCountryHeader) {
}
