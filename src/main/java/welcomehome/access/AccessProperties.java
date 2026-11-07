package welcomehome.access;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Guest flow settings.
 *
 * @param rateLimit      how many attempts to open a door are allowed
 * @param confirmation   how long to wait for the lock to confirm an opening
 * @param pin            how many wrong PINs a link allows
 * @see welcomehome.config.ClientProperties where the guest's IP address and country come from
 */
@ConfigurationProperties("welcomehome.access")
record AccessProperties(@NestedConfigurationProperty @DefaultValue RateLimit rateLimit,
                        @NestedConfigurationProperty @DefaultValue Confirmation confirmation,
                        @NestedConfigurationProperty @DefaultValue Pin pin) {

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
     * Wrong PINs allowed per link, from any IP address, as a 4 to 8 digit code can be guessed: once they are spent the
     * link refuses every attempt, a right PIN too, until they come back gradually over {@code period}.
     *
     * @param failures wrong PINs allowed
     * @param period   the period over which spent ones come back
     */
    record Pin(@DefaultValue("5") int failures, @DefaultValue("PT15M") Duration period) {
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
