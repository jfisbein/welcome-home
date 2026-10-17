package welcomehome.access;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Guest flow settings.
 *
 * @param rateLimit how many attempts to open a door are allowed
 */
@ConfigurationProperties("welcomehome.access")
record AccessProperties(@NestedConfigurationProperty @DefaultValue RateLimit rateLimit) {

    /**
     * Attempts to open a door allowed per period, counted per guest IP address and per link, so that
     * neither one device nor one leaked link can hammer a lock or Nuki.
     *
     * @param perIp   attempts per IP address
     * @param perLink attempts per link, from any IP address
     * @param period  the period the limits apply to; spent attempts come back gradually over it
     */
    record RateLimit(@DefaultValue("10") int perIp, @DefaultValue("5") int perLink, @DefaultValue("1m") Duration period) {
    }
}
