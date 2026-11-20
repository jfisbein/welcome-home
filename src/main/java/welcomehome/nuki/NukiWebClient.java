package welcomehome.nuki;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;

/**
 * The Nuki Web API, called with an owner's access token (an API token or an OAuth2 access token), which is sent
 * as a bearer token and never logged. {@link NukiRequests} says when a request is retried; the unlatch, also through
 * the Advanced API, only if Nuki cannot have run it. {@link NukiCodes} translates what Nuki answers.
 */
class NukiWebClient {

    private static final Logger log = LoggerFactory.getLogger(NukiWebClient.class);

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
                .map(smartlock -> {
                    var config = smartlock.config();
                    return new NukiDevice(smartlock.smartlockId(), NukiCodes.deviceType(smartlock.type()),
                            smartlock.name(), config == null ? null : NukiTimeZones.byId(config.timezoneId()),
                            config == null ? null : Coordinates.ofNuki(config.latitude(), config.longitude()));
                })
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
        var config = smartlock.config();
        var opener = NukiCodes.deviceType(smartlock.type()) == DeviceType.OPENER;
        if (smartlock.serverState() != SERVER_STATE_OK) {
            // Nuki's own code for why it cannot reach the device, which the app does not otherwise keep.
            log.warn("Nuki reports smartlock {} not reachable, serverState {}", smartlock.smartlockId(),
                    smartlock.serverState());
        }
        return new LockSnapshot(
                state == null ? LockPosition.UNKNOWN
                        : opener ? NukiCodes.openerPosition(state.state()) : NukiCodes.position(state.state()),
                smartlock.serverState() == SERVER_STATE_OK,
                state != null && Boolean.TRUE.equals(state.batteryCritical()),
                state == null ? null : state.batteryCharge(),
                advancedConfig != null && Boolean.TRUE.equals(advancedConfig.autoLock()),
                config == null ? null : Coordinates.ofNuki(config.latitude(), config.longitude()));
    }

    void unlatch(String accessToken, long smartlockId) {
        requests.call(false, () -> requests.rest().post().uri("/smartlock/{id}/action", smartlockId)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("action", UNLATCH))
                .retrieve()
                .toBodilessEntity());
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
    record NukiConfig(@Nullable Integer timezoneId, @Nullable Double latitude, @Nullable Double longitude) {
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
