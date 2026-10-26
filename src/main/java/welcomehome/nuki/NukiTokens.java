package welcomehome.nuki;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;

import org.jspecify.annotations.Nullable;

/**
 * An owner's OAuth2 tokens, as Nuki's token endpoint returns them. Never logged: {@link #toString()} leaves the
 * tokens out.
 *
 * @param accessToken  sent to Nuki as a bearer token
 * @param refreshToken gets new tokens when the access token expires; none if Nuki was not asked for
 *                     offline_access
 * @param expiresIn    how long the access token lasts from when Nuki sent it
 * @param scope        what the tokens allow, space-separated; none if it is what was asked for
 */
record NukiTokens(String accessToken, @Nullable String refreshToken, Duration expiresIn, @Nullable String scope) {

    /** How long Nuki's access tokens last. */
    static final Duration DEFAULT_LIFETIME = Duration.ofHours(1);

    /**
     * Tells whether the tokens allow everything asked for.
     *
     * @param scopes what was asked for
     * @return whether each of them was granted
     */
    boolean grantsAll(Collection<String> scopes) {
        // RFC 6749, section 5.1: a response without a scope grants what was asked for.
        return scope == null || Arrays.asList(scope.strip().split("\\s+")).containsAll(scopes);
    }

    @Override
    public String toString() {
        return "NukiTokens[expiresIn=" + expiresIn + ", scope=" + scope + "]";
    }
}
