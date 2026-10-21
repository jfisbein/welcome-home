package welcomehome.nuki;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
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
 * The Nuki Web API, called with an owner's API token, which is sent as a bearer token and never logged.
 * It translates Nuki's numeric codes into this module's types (see the API definition at
 * https://api.nuki.io/static/swagger/swagger.json).
 *
 * <p>Failures worth retrying are retried with exponential backoff: rate limiting (429, honoring
 * Retry-After), and for reads also server errors and timeouts. The unlatch action is retried only when Nuki
 * cannot have run it, that is, on 429 or when no connection was made, so that a door never opens twice. When it
 * may have run it, after a timeout or a server error, the failure says so with {@link Reason#NO_ANSWER}.
 */
class NukiWebClient {

    private static final int UNLATCH = 3;
    private static final int LOG_ENTRIES = 20;
    /** Nuki Box and Nuki Opener: not smart locks. */
    private static final Set<Integer> NOT_LOCKS = Set.of(1, 2);
    private static final int SERVER_STATE_OK = 0;

    private final RestClient rest;
    private final NukiProperties properties;

    NukiWebClient(NukiProperties properties) {
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

    List<SmartLock> listLocks(String apiToken) {
        List<NukiSmartlock> smartlocks = call(true, () -> rest.get().uri("/smartlock")
                .headers(headers -> headers.setBearerAuth(apiToken))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                }));
        return smartlocks.stream()
                .filter(smartlock -> !NOT_LOCKS.contains(smartlock.type()))
                .map(smartlock -> new SmartLock(smartlock.smartlockId(), smartlock.name(),
                        smartlock.config() == null ? null : NukiTimeZones.byId(smartlock.config().timezoneId())))
                .toList();
    }

    LockState getState(String apiToken, long smartlockId) {
        var smartlock = call(true, () -> rest.get().uri("/smartlock/{id}", smartlockId)
                .headers(headers -> headers.setBearerAuth(apiToken))
                .retrieve()
                .body(NukiSmartlock.class));
        var state = smartlock.state();
        var advancedConfig = smartlock.advancedConfig();
        return new LockState(
                state == null ? LockStatus.UNKNOWN : status(state.state()),
                smartlock.serverState() == SERVER_STATE_OK,
                state != null && Boolean.TRUE.equals(state.batteryCritical()),
                state == null ? null : state.batteryCharge(),
                advancedConfig != null && Boolean.TRUE.equals(advancedConfig.autoLock()));
    }

    void unlatch(String apiToken, long smartlockId) {
        call(false, () -> rest.post().uri("/smartlock/{id}/action", smartlockId)
                .headers(headers -> headers.setBearerAuth(apiToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("action", UNLATCH))
                .retrieve()
                .toBodilessEntity());
    }

    /** Reads the latest entry of the account's log, only to learn whether the token may read logs. */
    void checkLogAccess(String apiToken) {
        call(true, () -> rest.get().uri("/smartlock/log?limit={limit}", 1)
                .headers(headers -> headers.setBearerAuth(apiToken))
                .retrieve()
                .toBodilessEntity());
    }

    List<LockLogEntry> getRecentLog(String apiToken, long smartlockId) {
        List<NukiLogEntry> entries = call(true, () -> rest.get()
                .uri("/smartlock/{id}/log?limit={limit}", smartlockId, LOG_ENTRIES)
                .headers(headers -> headers.setBearerAuth(apiToken))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                }));
        return entries.stream()
                .map(entry -> new LockLogEntry(entry.date(), action(entry.action()), trigger(entry.trigger()),
                        actionState(entry.state())))
                .toList();
    }

    /**
     * Runs a request, retrying it as the class comment describes.
     *
     * @param safeToRepeat whether the request has no effect on the lock, so a failure after it was sent
     *                     may be retried
     */
    private <T> T call(boolean safeToRepeat, Supplier<@Nullable T> request) {
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

    static LockStatus status(int nukiState) {
        return switch (nukiState) {
            case 1 -> LockStatus.LOCKED;
            case 3, 6 -> LockStatus.UNLOCKED;
            case 5 -> LockStatus.UNLATCHED;
            case 2, 4, 7 -> LockStatus.MOVING;
            case 254 -> LockStatus.MOTOR_BLOCKED;
            default -> LockStatus.UNKNOWN;
        };
    }

    static LockActionState actionState(int nukiState) {
        return switch (nukiState) {
            case 0 -> LockActionState.SUCCEEDED;
            case 1 -> LockActionState.MOTOR_BLOCKED;
            case 10 -> LockActionState.REJECTED_NIGHT_MODE;
            default -> LockActionState.FAILED;
        };
    }

    static LockTrigger trigger(int nukiTrigger) {
        return switch (nukiTrigger) {
            case 0 -> LockTrigger.SYSTEM;
            case 1 -> LockTrigger.MANUAL;
            case 2 -> LockTrigger.BUTTON;
            case 3 -> LockTrigger.AUTOMATIC;
            case 4 -> LockTrigger.WEB;
            case 5 -> LockTrigger.APP;
            case 6 -> LockTrigger.AUTO_LOCK;
            case 7 -> LockTrigger.ACCESSORY;
            default -> LockTrigger.OTHER;
        };
    }

    static LockAction action(int nukiAction) {
        return switch (nukiAction) {
            case 1 -> LockAction.UNLOCK;
            case 2 -> LockAction.LOCK;
            case 3 -> LockAction.UNLATCH;
            case 240 -> LockAction.DOOR_OPENED;
            case 241 -> LockAction.DOOR_CLOSED;
            default -> LockAction.OTHER;
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NukiSmartlock(long smartlockId, int type, String name, int serverState, @Nullable NukiState state,
                         @Nullable NukiConfig config, @Nullable NukiAdvancedConfig advancedConfig) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NukiConfig(@Nullable Integer timezoneId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NukiState(int state, @Nullable Boolean batteryCritical, @Nullable Integer batteryCharge) {
    }

    /** autoLock exists from firmware 2.7.8 / 1.9.1 on; older locks are reported without Auto Lock. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record NukiAdvancedConfig(@Nullable Boolean autoLock) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NukiLogEntry(Instant date, int action, int trigger, int state) {
    }
}
