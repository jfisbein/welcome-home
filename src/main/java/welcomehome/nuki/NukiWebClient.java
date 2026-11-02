package welcomehome.nuki;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import welcomehome.nuki.LockProviderException.Reason;

/**
 * The Nuki Web API, called with an owner's access token (an API token or an OAuth2 access token), which is sent
 * as a bearer token and never logged. {@link NukiRequests} says when a request is retried; the unlatch, also through
 * the Advanced API, only if Nuki cannot have run it. {@link NukiCodes} translates what Nuki answers.
 */
class NukiWebClient {

    private static final Logger log = LoggerFactory.getLogger(NukiWebClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Unlatch on a smart lock; on an Opener, actuate the electric strike, which opens the entrance. */
    private static final int UNLATCH = 3;
    private static final int LOG_ENTRIES = 20;
    /** Nuki Box: nothing to open. */
    private static final int BOX = 1;
    private static final int SERVER_STATE_OK = 0;

    private final NukiRequests requests;

    NukiWebClient(NukiRequests requests) {
        this.requests = requests;
    }

    List<NukiDevice> listLocks(String accessToken) {
        return devices(accessToken).stream()
                .map(smartlock -> new NukiDevice(smartlock.smartlockId(), NukiCodes.deviceType(smartlock.type()),
                        smartlock.name(),
                        smartlock.config() == null ? null : NukiTimeZones.byId(smartlock.config().timezoneId())))
                .toList();
    }

    LockSnapshot getSnapshot(String accessToken, long smartlockId) {
        return lockState(requests.call(true, () -> requests.rest().get().uri("/smartlock/{id}", smartlockId)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(NukiSmartlock.class)));
    }

    Map<Long, LockSnapshot> listSnapshots(String accessToken) {
        return devices(accessToken).stream()
                .collect(Collectors.toMap(NukiSmartlock::smartlockId, NukiWebClient::lockState));
    }

    /** The account's smart locks and Openers, with all that Nuki says of each in one request. */
    private List<NukiSmartlock> devices(String accessToken) {
        List<NukiSmartlock> smartlocks = requests.call(true, () -> requests.rest().get().uri("/smartlock")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                }));
        return smartlocks.stream().filter(smartlock -> smartlock.type() != BOX).toList();
    }

    private static LockSnapshot lockState(NukiSmartlock smartlock) {
        var state = smartlock.state();
        var advancedConfig = smartlock.advancedConfig();
        var opener = NukiCodes.deviceType(smartlock.type()) == DeviceType.OPENER;
        return new LockSnapshot(
                state == null ? LockPosition.UNKNOWN
                        : opener ? NukiCodes.openerPosition(state.state()) : NukiCodes.position(state.state()),
                smartlock.serverState() == SERVER_STATE_OK,
                state != null && Boolean.TRUE.equals(state.batteryCritical()),
                state == null ? null : state.batteryCharge(),
                advancedConfig != null && Boolean.TRUE.equals(advancedConfig.autoLock()));
    }

    void unlatch(String accessToken, long smartlockId) {
        requests.call(false, () -> requests.rest().post().uri("/smartlock/{id}/action", smartlockId)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("action", UNLATCH))
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * Asks Nuki to unlatch with its Advanced API, which reports the outcome with a webhook to welcome-home's
     * central webhook URL once the lock has run it. The request is retried as {@link #unlatch} is.
     *
     * @return the id of the request, which the webhook carries; empty if Nuki refused the request (an HTTP status
     * 4xx other than 429), and so ran nothing
     * @throws LockProviderException as {@link #unlatch} does; with {@link Reason#NO_ANSWER} also when Nuki accepted
     *                               the request but its answer carries no request id
     */
    Optional<String> unlatchReported(String accessToken, long smartlockId) {
        String answer;
        try {
            answer = requests.call(false, () -> {
                var body = requests.rest().post().uri("/smartlock/{id}/action/advanced", smartlockId)
                        .headers(headers -> headers.setBearerAuth(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("action", UNLATCH))
                        .retrieve()
                        .toEntity(String.class)
                        .getBody();
                // An empty answer is still an accepted request, unlike for the calls that read.
                return body == null ? "" : body;
            });
        } catch (LockProviderException e) {
            if (e.getCause() instanceof RestClientResponseException refused
                    && refused.getStatusCode().is4xxClientError()
                    && !refused.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
                log.info("Nuki refused to unlatch smartlock {} with its Advanced API: {}{}", smartlockId,
                        refused.getStatusCode().value(), NukiRequests.reason(refused));
                return Optional.empty();
            }
            throw e;
        }
        // Accepted: whatever the answer says, the lock may unlatch, so only its log can tell.
        try {
            var requestId = JSON.readTree(answer).path("requestId");
            if (requestId.isString() && !requestId.stringValue().isBlank()) {
                return Optional.of(requestId.stringValue());
            }
        } catch (JacksonException ignored) {
            // As with no request id.
        }
        throw new LockProviderException(Reason.NO_ANSWER);
    }

    /** Reads the latest entry of the account's log, only to learn whether the token may read logs. */
    void checkLogAccess(String accessToken) {
        requests.call(true, () -> requests.rest().get().uri("/smartlock/log?limit={limit}", 1)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .toBodilessEntity());
    }

    List<LockLogEntry> getRecentLog(String accessToken, long smartlockId) {
        List<NukiLogEntry> entries = requests.call(true, () -> requests.rest().get()
                .uri("/smartlock/{id}/log?limit={limit}", smartlockId, LOG_ENTRIES)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                }));
        return entries.stream()
                .map(entry -> new LockLogEntry(entry.date(), NukiCodes.action(entry.action()),
                        NukiCodes.trigger(entry.trigger()), NukiCodes.actionResult(entry.state()),
                        entry.action() + "/" + entry.trigger() + "/" + entry.state()))
                .toList();
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
