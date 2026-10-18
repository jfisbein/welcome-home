package welcomehome.access;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Guest flow settings.
 *
 * @param rateLimit      how many attempts to open a door are allowed
 * @param confirmation   how long to wait for the lock to confirm an opening
 * @param clientIpHeader the header that holds the guest's IP address, set by the proxy in front of the app;
 *                       blank to use the address the request came from
 */
@ConfigurationProperties("welcomehome.access")
record AccessProperties(@NestedConfigurationProperty @DefaultValue RateLimit rateLimit,
                        @NestedConfigurationProperty @DefaultValue Confirmation confirmation,
                        @DefaultValue("CF-Connecting-IP") @Nullable String clientIpHeader) {

    /**
     * Attempts to open a door allowed per period, counted per guest IP address and per link, so that
     * neither one device nor one leaked link can hammer a lock or Nuki.
     *
     * @param perIp   attempts per IP address
     * @param perLink attempts per link, from any IP address
     * @param period  the period the limits apply to; spent attempts come back gradually over it
     */
    record RateLimit(@DefaultValue("10") int perIp, @DefaultValue("5") int perLink, @DefaultValue("PT1M") Duration period) {
    }

    /**
     * Nuki runs actions asynchronously: after accepting an unlatch, the app reads the lock's log until the
     * action shows up or {@code timeout} passes.
     *
     * @param timeout  how long to wait for the lock's log to show the unlatch
     * @param interval the wait between two reads of the log
     */
    record Confirmation(@DefaultValue("PT15S") Duration timeout, @DefaultValue("PT2S") Duration interval) {
    }
}
