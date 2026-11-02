package welcomehome.nuki;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import welcomehome.nuki.LockProviderException.Reason;

/**
 * welcome-home's OAuth2 client at Nuki (https://docs.nuki.io/guide/authorization/oauth2): where to send an owner to
 * authorize it, and the tokens that Nuki gives for that and takes back. Token requests are never repeated once Nuki
 * may have answered: it takes an authorization code only once and rotates the refresh token on every refresh.
 */
class NukiOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(NukiOAuthClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What welcome-home needs from Nuki: to list locks, open them, read their logs, and keep its access. */
    static final List<String> SCOPES = List.of("smartlock.readOnly", "smartlock.action", "smartlock.log",
            "offline_access");

    /**
     * What welcome-home also asks for, without needing it: Nuki reports the Advanced API's actions only to central
     * webhooks, which need it. A client whose Advanced API has no webhooks may not ask for it.
     */
    static final String WEBHOOKS = "webhook.central";

    /** The token endpoint's errors that are about welcome-home's client, not about the owner's code or tokens. */
    private static final Set<String> CLIENT_ERRORS = Set.of("invalid_client", "unauthorized_client");

    private final NukiRequests requests;
    private final NukiProperties properties;

    NukiOAuthClient(NukiRequests requests, NukiProperties properties) {
        this.requests = requests;
        this.properties = properties;
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

    /**
     * Revokes an authorization at Nuki with its refresh token, which also ends its access tokens (RFC 7009, section
     * 2.1). Without a client there is nothing to revoke with, nor any authorization of welcome-home's to revoke.
     *
     * @throws LockProviderException if Nuki does not confirm it
     */
    void revoke(String refreshToken) {
        var oauth = properties.oauth();
        if (!oauth.enabled()) {
            return;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("token", refreshToken);
        form.add("token_type_hint", "refresh_token");
        form.add("client_id", oauth.clientId());
        form.add("client_secret", oauth.clientSecret());
        requests.call(false, () -> requests.rest().post().uri(properties.revocationUrl())
                .headers(headers -> headers.setBasicAuth(oauth.clientId(), oauth.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity());
    }

    /** With the client's credentials both as HTTP Basic and in the form, as Nuki was seen to accept them. */
    private NukiTokens requestTokens(MultiValueMap<String, String> form) {
        var oauth = properties.oauth();
        form.add("client_id", oauth.clientId());
        form.add("client_secret", oauth.clientSecret());
        NukiTokenResponse response;
        try {
            response = requests.call(false, () -> requests.rest().post().uri("/oauth/token")
                    .headers(headers -> headers.setBasicAuth(oauth.clientId(), oauth.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(NukiTokenResponse.class));
        } catch (LockProviderException e) {
            if (e.getCause() instanceof RestClientResponseException refused) {
                // About welcome-home itself, such as a wrong client secret: no owner's tokens are at fault.
                if (refusesTheClient(refused)) {
                    log.error("Nuki refused welcome-home's OAuth2 client: {}{}", refused.getStatusCode().value(),
                            NukiRequests.reason(refused));
                    throw new LockProviderException(Reason.NUKI_ERROR, refused);
                }
                // invalid_grant, and every other refusal of the request: there are no tokens to be had with it.
                if (refused.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST)) {
                    throw new LockProviderException(Reason.ACCESS_DENIED, refused);
                }
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

    /** RFC 6749, section 5.2: a 401 is always about the client; these two errors may also come as a 400. */
    private static boolean refusesTheClient(RestClientResponseException refused) {
        if (refused.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
            return true;
        }
        try {
            var error = JSON.readTree(refused.getResponseBodyAsString(StandardCharsets.UTF_8)).path("error");
            return error.isString() && CLIENT_ERRORS.contains(error.stringValue());
        } catch (JacksonException ignored) {
            return false;
        }
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
