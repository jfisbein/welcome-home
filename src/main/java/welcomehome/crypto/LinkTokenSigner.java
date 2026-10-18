package welcomehome.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import com.google.crypto.tink.Mac;
import org.springframework.stereotype.Component;

/**
 * Signs access links. A token is {@code {link_id}.{mac}}, with {@code mac = HMAC(key, link_id || token_version)}
 * in base64url: nothing is stored, the token can be recomputed to copy the link again, and incrementing
 * {@code token_version} invalidates every earlier token.
 * <p>
 * The guest flow reads the link id with {@link #parseLinkId(String)}, looks up the link's current
 * {@code token_version}, and trusts the link only after {@link #verify(String, int)}.
 */
@Component
public class LinkTokenSigner {

    private static final int MAX_TOKEN_LENGTH = 100;
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final Mac mac;

    LinkTokenSigner(Mac mac) {
        this.mac = mac;
    }

    /**
     * Creates the token of a link.
     *
     * @param linkId       the access link's id
     * @param tokenVersion the access link's current token version
     * @return the token, {@code {link_id}.{mac}}
     */
    public String sign(UUID linkId, int tokenVersion) {
        try {
            return linkId + "." + BASE64_URL.encodeToString(mac.computeMac(signedData(linkId, tokenVersion)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign access link " + linkId, e);
        }
    }

    /**
     * Reads the link id of a token without checking its MAC: the result is not to be trusted until
     * {@link #verify(String, int)} accepts the token.
     *
     * @param token the token from the link's URL
     * @return the link id, or empty if the token is malformed
     */
    public Optional<UUID> parseLinkId(String token) {
        return parse(token).map(ParsedToken::linkId);
    }

    /**
     * Checks a token's MAC against the link's current token version, in constant time.
     *
     * @param token        the token from the link's URL
     * @param tokenVersion the link's current token version
     * @return whether the token is well formed and its MAC is valid for that version
     */
    public boolean verify(String token, int tokenVersion) {
        return parse(token)
                .map(parsed -> isValidMac(parsed, tokenVersion))
                .orElse(false);
    }

    private boolean isValidMac(ParsedToken token, int tokenVersion) {
        try {
            mac.verifyMac(token.mac(), signedData(token.linkId(), tokenVersion));
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static Optional<ParsedToken> parse(String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        var parts = token.split("\\.", -1);
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            var linkId = UUID.fromString(parts[0]);
            if (!linkId.toString().equals(parts[0])) {
                return Optional.empty();
            }
            return Optional.of(new ParsedToken(linkId, Base64.getUrlDecoder().decode(parts[1])));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] signedData(UUID linkId, int tokenVersion) {
        return ByteBuffer.allocate(Long.BYTES * 2 + Integer.BYTES)
                .putLong(linkId.getMostSignificantBits())
                .putLong(linkId.getLeastSignificantBits())
                .putInt(tokenVersion)
                .array();
    }

    // Never compared, so the array's identity-based equals does not matter.
    @SuppressWarnings("ArrayRecordComponent")
    private record ParsedToken(UUID linkId, byte[] mac) {
    }
}
