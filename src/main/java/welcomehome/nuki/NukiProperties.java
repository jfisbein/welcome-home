package welcomehome.nuki;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How to reach the Nuki Web API. The defaults keep a guest's wait short: a failing call gives up after
 * {@code maxAttempts} attempts.
 *
 * @param baseUrl        the Nuki Web API, which also authorizes OAuth2 clients
 * @param connectTimeout how long to wait for a connection
 * @param readTimeout    how long to wait for a response
 * @param maxAttempts    how many times to try a call that failed for a reason worth retrying
 * @param initialBackoff the wait before the second attempt; it doubles for each further attempt
 * @param maxBackoff     the longest wait between attempts, also for Nuki's Retry-After
 * @param oauth          welcome-home's OAuth2 client at Nuki
 */
@ConfigurationProperties("welcomehome.nuki")
record NukiProperties(
        @DefaultValue("https://api.nuki.io") URI baseUrl,
        @DefaultValue("PT5S") Duration connectTimeout,
        @DefaultValue("PT10S") Duration readTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("PT0.5S") Duration initialBackoff,
        @DefaultValue("PT5S") Duration maxBackoff,
        @DefaultValue OAuth oauth) {

    /**
     * welcome-home's OAuth2 client, which Nuki grants with its Advanced API (Nuki Web, API). Without it,
     * owners connect Nuki only by pasting an API token.
     *
     * @param clientId     the client id, blank if there is none
     * @param clientSecret the client secret, blank if there is none
     */
    record OAuth(@DefaultValue("") String clientId, @DefaultValue("") String clientSecret) {

        OAuth {
            // Read from files, which usually end with a newline.
            clientId = clientId.strip();
            clientSecret = clientSecret.strip();
        }

        /**
         * Tells whether owners may connect Nuki with OAuth2.
         *
         * @return whether there are a client id and secret
         */
        boolean enabled() {
            return !clientId.isEmpty() && !clientSecret.isEmpty();
        }

        @Override
        public String toString() {
            return "OAuth[clientId=" + clientId + ", clientSecret=" + (clientSecret.isEmpty() ? "" : "***") + "]";
        }
    }
}
