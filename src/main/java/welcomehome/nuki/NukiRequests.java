package welcomehome.nuki;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import welcomehome.nuki.LockProviderException.Reason;

/**
 * How every request reaches Nuki: its timeouts, and what a failure means.
 *
 * <p>Failures worth retrying are retried with exponential backoff: rate limiting (429, honoring
 * Retry-After), and for reads also server errors and timeouts. A request with an effect is retried only when Nuki
 * cannot have received it, that is, on 429 or when no connection was made, so that a door never opens twice. When it
 * may have run it, after a timeout or a server error, the failure says so with {@link Reason#NO_ANSWER}.
 */
class NukiRequests {

    /** As much of Nuki's reason for a refusal as is logged. */
    private static final int REASON_LENGTH = 300;

    private final RestClient rest;
    private final NukiProperties properties;

    NukiRequests(NukiProperties properties) {
        this.properties = properties;
        // HTTP/1.1: no HTTP/2 upgrade attempts, which some servers and proxies cut off.
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.rest = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "welcome-home")
                .build();
    }

    RestClient rest() {
        return rest;
    }

    /**
     * Runs a request, retrying it as the class comment describes.
     *
     * @param safeToRepeat whether the request has no effect on the lock, so a failure after it was sent
     *                     may be retried
     */
    <T> T call(boolean safeToRepeat, Supplier<@Nullable T> request) {
        var backoff = properties.initialBackoff();
        for (int attempt = 1; ; attempt++) {
            Duration wait;
            try {
                var result = request.get();
                if (result == null) {
                    throw new LockProviderException(Reason.NUKI_ERROR);
                }
                return result;
            } catch (RestClientResponseException e) {
                var status = HttpStatus.resolve(e.getStatusCode().value());
                if (status == HttpStatus.UNAUTHORIZED) {
                    throw new LockProviderException(Reason.ACCESS_DENIED, e);
                }
                if (status == HttpStatus.FORBIDDEN) {
                    throw new LockProviderException(Reason.MISSING_PERMISSION, e);
                }
                if (status == HttpStatus.NOT_FOUND) {
                    throw new LockProviderException(Reason.LOCK_NOT_FOUND, e);
                }
                if (!safeToRepeat && e.getStatusCode().is5xxServerError()) {
                    throw new LockProviderException(Reason.NO_ANSWER, e);
                }
                boolean retry = status == HttpStatus.TOO_MANY_REQUESTS || (safeToRepeat && e.getStatusCode().is5xxServerError());
                if (!retry || attempt == properties.maxAttempts()) {
                    throw new LockProviderException(Reason.NUKI_ERROR, e);
                }
                wait = status == HttpStatus.TOO_MANY_REQUESTS ? max(backoff, retryAfter(e)) : backoff;
            } catch (ResourceAccessException e) {
                if (!safeToRepeat && !notConnected(e)) {
                    throw new LockProviderException(Reason.NO_ANSWER, e);
                }
                if (attempt == properties.maxAttempts()) {
                    throw new LockProviderException(Reason.NUKI_ERROR, e);
                }
                wait = backoff;
            } catch (RestClientException e) {
                throw new LockProviderException(Reason.NUKI_ERROR, e);
            }
            sleep(min(wait, properties.maxBackoff()));
            backoff = backoff.multipliedBy(2);
        }
    }

    private static boolean notConnected(ResourceAccessException e) {
        return e.getCause() instanceof ConnectException || e.getCause() instanceof HttpConnectTimeoutException;
    }

    /** Retry-After in seconds; Nuki does not send the HTTP-date form. */
    private static Duration retryAfter(RestClientResponseException e) {
        var headers = e.getResponseHeaders();
        var value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        try {
            return value == null ? Duration.ZERO : Duration.ofSeconds(Long.parseLong(value.strip()));
        } catch (NumberFormatException ignored) {
            return Duration.ZERO;
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockProviderException(Reason.NUKI_ERROR, e);
        }
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /**
     * Nuki's reason for refusing a request, for the log: its {@code WWW-Authenticate} challenge and its answer, on
     * one line and cut short, or nothing if it gave none. Only Nuki's answer is logged, never the request and its token.
     */
    static String reason(RestClientResponseException refused) {
        var headers = refused.getResponseHeaders();
        var challenge = headers == null ? null : headers.getFirst(HttpHeaders.WWW_AUTHENTICATE);
        var answer = refused.getResponseBodyAsString(StandardCharsets.UTF_8);
        var text = ((challenge == null ? "" : challenge) + " " + answer).replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (text.isEmpty()) {
            return "";
        }
        return " (" + (text.length() > REASON_LENGTH ? text.substring(0, REASON_LENGTH) + "..." : text) + ")";
    }
}
