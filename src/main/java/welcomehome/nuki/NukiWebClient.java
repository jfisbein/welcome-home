package welcomehome.nuki;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import welcomehome.nuki.LockProviderException.Reason;

/**
 * The Nuki Web API, called with an owner's access token (an API token or an OAuth2 access token), which is sent
 * as a bearer token and never logged. It translates Nuki's numeric codes into this module's types (see the API
 * definition at https://api.nuki.io/static/swagger/swagger.json), and gets OAuth2 tokens from Nuki's token
 * endpoint (https://docs.nuki.io/guide/authorization/oauth2).
 *
 * <p>Failures worth retrying are retried with exponential backoff: rate limiting (429, honoring
 * Retry-After), and for reads also server errors and timeouts. The unlatch action is retried only when Nuki
 * cannot have run it, that is, on 429 or when no connection was made, so that a door never opens twice. When it
 * may have run it, after a timeout or a server error, the failure says so with {@link Reason#NO_ANSWER}. The same
 * holds for the unlatch through the Advanced API, whose outcome Nuki reports with a webhook. Token
 * requests are retried the same way: Nuki takes an authorization code only once and rotates the refresh token
 * on every refresh, so a request it may have answered is not sent again.
 */
class NukiWebClient {

    private static final Logger log = LoggerFactory.getLogger(NukiWebClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Unlatch on a smart lock; on an Opener, actuate the electric strike, which opens the entrance. */
    private static final int UNLATCH = 3;
    private static final int LOG_ENTRIES = 20;
    /** Nuki Box: nothing to open. */
    private static final int BOX = 1;
    private static final int OPENER = 2;
    private static final int SERVER_STATE_OK = 0;

    /** What welcome-home needs from Nuki: to list locks, open them, read their logs, and keep its access. */
    static final List<String> SCOPES = List.of("smartlock.readOnly", "smartlock.action", "smartlock.log",
            "offline_access");

    /**
     * What welcome-home also asks for, without needing it: Nuki reports the Advanced API's actions only to central
     * webhooks, which need it, and refused an advanced unlatch with a 401 to a token without it (2026-10-28). A
     * client whose Advanced API has no webhooks may not ask for it.
     */
    static final String WEBHOOKS = "webhook.central";

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
                .filter(smartlock -> smartlock.type() != BOX)
                .map(smartlock -> new SmartLock(smartlock.smartlockId(), deviceType(smartlock.type()), smartlock.name(),
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
        var opener = deviceType(smartlock.type()) == DeviceType.OPENER;
        return new LockState(
                state == null ? LockStatus.UNKNOWN : opener ? openerStatus(state.state()) : status(state.state()),
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
            answer = call(false, () -> {
                var body = rest.post().uri("/smartlock/{id}/action/advanced", smartlockId)
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
                log.info("Nuki refused to unlatch smartlock {} with its Advanced API: {}", smartlockId,
                        refused.getStatusCode().value());
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
    void checkLogAccess(String apiToken) {
        call(true, () -> rest.get().uri("/smartlock/log?limit={limit}", 1)
                .headers(headers -> headers.setBearerAuth(apiToken))
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * Where to send an owner to authorize welcome-home in Nuki. The request carries the PKCE challenge of the
     * code verifier (RFC 7636, method S256), so that only who holds the verifier can exchange the code.
     *
     * @param redirectUri  where Nuki sends the owner back, one of the client's callback URLs in Nuki Web
     * @param state        what Nuki sends back unchanged, to tie its answer to the request
     * @param codeVerifier the secret that {@link #exchangeCode} sends with the code
     * @param withWebhooks whether to ask for {@link #WEBHOOKS} too
     */
    URI authorizationUri(URI redirectUri, String state, String codeVerifier, boolean withWebhooks) {
        var scopes = withWebhooks ? String.join(" ", SCOPES) + " " + WEBHOOKS : String.join(" ", SCOPES);
        return UriComponentsBuilder.fromUri(properties.baseUrl())
                .path("/oauth/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", "{clientId}")
                .queryParam("redirect_uri", "{redirectUri}")
                .queryParam("scope", "{scope}")
                .queryParam("state", "{state}")
                .queryParam("code_challenge", "{codeChallenge}")
                .queryParam("code_challenge_method", "S256")
                .encode()
                .buildAndExpand(properties.oauth().clientId(), redirectUri, scopes, state,
                        codeChallenge(codeVerifier))
                .toUri();
    }

    /** RFC 7636, section 4.2: the verifier's SHA-256, in base64url without padding. */
    static String codeChallenge(String codeVerifier) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform has SHA-256", e);
        }
    }

    /**
     * Exchanges the code that Nuki sent back with the owner for the owner's tokens.
     *
     * @param codeVerifier the verifier whose challenge went to Nuki with the owner
     * @throws LockProviderException with {@link Reason#ACCESS_DENIED} if Nuki refuses the code, for example
     *                               because it expired, was used already or the verifier does not match it
     */
    NukiTokens exchangeCode(String code, String codeVerifier, URI redirectUri) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("redirect_uri", redirectUri.toString());
        return requestTokens(form);
    }

    /**
     * Gets new tokens with the refresh token. Nuki rotates it: the one returned replaces the one sent.
     *
     * @throws LockProviderException with {@link Reason#ACCESS_DENIED} if Nuki refuses the refresh token, for
     *                               example because the owner revoked welcome-home's access
     */
    NukiTokens refresh(String refreshToken) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return requestTokens(form);
    }

    /** With the client's credentials both as HTTP Basic and in the form, as Nuki was seen to accept them. */
    private NukiTokens requestTokens(MultiValueMap<String, String> form) {
        var oauth = properties.oauth();
        form.add("client_id", oauth.clientId());
        form.add("client_secret", oauth.clientSecret());
        NukiTokenResponse response;
        try {
            response = call(false, () -> rest.post().uri("/oauth/token")
                    .headers(headers -> headers.setBasicAuth(oauth.clientId(), oauth.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(NukiTokenResponse.class));
        } catch (LockProviderException e) {
            // invalid_grant, and every other refusal of the request: there are no tokens to be had with it.
            if (e.getCause() instanceof RestClientResponseException refused
                    && refused.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST)) {
                throw new LockProviderException(Reason.ACCESS_DENIED, refused);
            }
            throw e;
        }
        var accessToken = response.accessToken();
        if (accessToken == null || accessToken.isBlank() || !"bearer".equalsIgnoreCase(response.tokenType())) {
            throw new LockProviderException(Reason.NUKI_ERROR);
        }
        // Nuki's access tokens last an hour; one with no lifetime stated is taken to last as long.
        var expiresIn = response.expiresIn() == null ? NukiTokens.DEFAULT_LIFETIME
                : Duration.ofSeconds(response.expiresIn());
        return new NukiTokens(accessToken, response.refreshToken(), expiresIn, response.scope());
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
                        actionState(entry.state()), entry.action() + "/" + entry.trigger() + "/" + entry.state()))
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

    /** An Opener's states are its own: it locks nothing, and opens the entrance only for a few seconds. */
    static LockStatus openerStatus(int nukiState) {
        return switch (nukiState) {
            case 1 -> LockStatus.READY;
            case 3 -> LockStatus.RING_TO_OPEN;
            case 5, 7 -> LockStatus.OPENING;
            default -> LockStatus.UNKNOWN;
        };
    }

    /** Smart Locks of every generation, and the Smart Door, are smart locks; only an Opener is not. */
    static DeviceType deviceType(int nukiType) {
        return nukiType == OPENER ? DeviceType.OPENER : DeviceType.SMART_LOCK;
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NukiTokenResponse(@JsonProperty("access_token") @Nullable String accessToken,
                             @JsonProperty("token_type") @Nullable String tokenType,
                             @JsonProperty("expires_in") @Nullable Long expiresIn,
                             @JsonProperty("refresh_token") @Nullable String refreshToken,
                             @Nullable String scope) {

        @Override
        public String toString() {
            return "NukiTokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + ", scope=" + scope + "]";
        }
    }
}
