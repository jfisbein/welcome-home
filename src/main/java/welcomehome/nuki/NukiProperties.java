package welcomehome.nuki;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How to reach the Nuki Web API. The defaults keep a guest's wait short: a failing call gives up after
 * {@code maxAttempts} attempts.
 *
 * @param baseUrl        the Nuki Web API
 * @param connectTimeout how long to wait for a connection
 * @param readTimeout    how long to wait for a response
 * @param maxAttempts    how many times to try a call that failed for a reason worth retrying
 * @param initialBackoff the wait before the second attempt; it doubles for each further attempt
 * @param maxBackoff     the longest wait between attempts, also for Nuki's Retry-After
 */
@ConfigurationProperties("welcomehome.nuki")
record NukiProperties(
        @DefaultValue("https://api.nuki.io") URI baseUrl,
        @DefaultValue("PT5S") Duration connectTimeout,
        @DefaultValue("PT10S") Duration readTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("PT0.5S") Duration initialBackoff,
        @DefaultValue("PT5S") Duration maxBackoff) {
}
