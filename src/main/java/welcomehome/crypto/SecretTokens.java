package welcomehome.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import org.jspecify.annotations.Nullable;

/**
 * Random tokens that are a credential by themselves, such as an invitation's or the link that verifies an email:
 * 256 random bits in base64url. What keeps one stores only its SHA-256, which is enough for this much entropy: no
 * salt and no slow hash, as there is nothing to guess.
 */
public final class SecretTokens {

    /** Longer input is not a token and is not even hashed. Real tokens have 43 characters. */
    public static final int MAX_LENGTH = 100;

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretTokens() {
    }

    /**
     * Makes a token.
     *
     * @return 32 random bytes, in base64url without padding
     */
    public static String newToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Hashes a token as it is stored and looked up.
     *
     * @param token a token, or what someone sent as one
     * @return its SHA-256
     */
    public static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Tells whether a text may be a token at all, before anything is hashed or looked up.
     *
     * @param token what someone sent as a token, or null
     * @return whether it is neither empty nor longer than any token
     */
    public static boolean isPlausible(@Nullable String token) {
        return token != null && !token.isEmpty() && token.length() <= MAX_LENGTH;
    }
}
