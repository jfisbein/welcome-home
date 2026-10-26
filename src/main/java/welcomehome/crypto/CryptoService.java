package welcomehome.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;

import com.google.crypto.tink.Aead;
import org.springframework.stereotype.Service;

/**
 * Encrypts each owner's Nuki tokens with AEAD (AES-256-GCM): the token sent to Nuki, an API token or an OAuth2
 * access token, and an OAuth2 refresh token. The owner's id is bound as associated data, so a ciphertext copied
 * into another owner's row cannot be decrypted, and so is the kind of token, so neither stands in for the other.
 */
@Service
public class CryptoService {

    /** Named after the API tokens it was first used for, and kept, as tokens stored with it must decrypt. */
    private static final String ACCESS_TOKEN_CONTEXT = "nuki-api-token:";
    private static final String REFRESH_TOKEN_CONTEXT = "nuki-refresh-token:";

    private final Aead aead;

    CryptoService(Aead aead) {
        this.aead = aead;
    }

    /**
     * Encrypts the token that one owner's calls send to Nuki: an API token or an OAuth2 access token.
     *
     * @param ownerId     the owner the token belongs to
     * @param accessToken the token in clear text
     * @return the ciphertext, to be stored in {@code nuki_connection.access_token_encrypted}
     */
    public byte[] encryptAccessToken(UUID ownerId, String accessToken) {
        return encrypt(accessToken, associatedData(ACCESS_TOKEN_CONTEXT, ownerId));
    }

    /**
     * Decrypts the token that one owner's calls send to Nuki.
     *
     * @param ownerId   the owner the token was encrypted for
     * @param encrypted the ciphertext from {@link #encryptAccessToken(UUID, String)}
     * @return the token in clear text
     * @throws DecryptionFailedException if the token was encrypted for another owner, was altered, or its key
     *                                   is no longer in the keyset
     */
    public String decryptAccessToken(UUID ownerId, byte[] encrypted) {
        return decrypt(encrypted, associatedData(ACCESS_TOKEN_CONTEXT, ownerId), ownerId);
    }

    /**
     * Encrypts one owner's OAuth2 refresh token.
     *
     * @param ownerId      the owner the token belongs to
     * @param refreshToken the token in clear text
     * @return the ciphertext, to be stored in {@code nuki_connection.refresh_token_encrypted}
     */
    public byte[] encryptRefreshToken(UUID ownerId, String refreshToken) {
        return encrypt(refreshToken, associatedData(REFRESH_TOKEN_CONTEXT, ownerId));
    }

    /**
     * Decrypts one owner's OAuth2 refresh token.
     *
     * @param ownerId   the owner the token was encrypted for
     * @param encrypted the ciphertext from {@link #encryptRefreshToken(UUID, String)}
     * @return the token in clear text
     * @throws DecryptionFailedException if the token was encrypted for another owner or as an access token, was
     *                                   altered, or its key is no longer in the keyset
     */
    public String decryptRefreshToken(UUID ownerId, byte[] encrypted) {
        return decrypt(encrypted, associatedData(REFRESH_TOKEN_CONTEXT, ownerId), ownerId);
    }

    private byte[] encrypt(String token, byte[] associatedData) {
        try {
            return aead.encrypt(token.getBytes(StandardCharsets.UTF_8), associatedData);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt a Nuki token", e);
        }
    }

    private String decrypt(byte[] encrypted, byte[] associatedData, UUID ownerId) {
        try {
            return new String(aead.decrypt(encrypted, associatedData), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new DecryptionFailedException("Cannot decrypt a Nuki token of owner " + ownerId, e);
        }
    }

    private static byte[] associatedData(String context, UUID ownerId) {
        return (context + ownerId).getBytes(StandardCharsets.UTF_8);
    }
}
