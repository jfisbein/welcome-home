package welcomehome.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.UUID;

import com.google.crypto.tink.Aead;
import org.springframework.stereotype.Service;

/**
 * Encrypts each owner's secrets with AEAD (AES-256-GCM): their Nuki tokens, the token sent to Nuki, an API token or an
 * OAuth2 access token, and an OAuth2 refresh token, and the PINs of their links. The owner's id is bound as associated data, so a ciphertext copied
 * into another owner's row cannot be decrypted, and so is the kind of token, so neither stands in for the other.
 * <p>
 * Tokens are encrypted with the keyset's primary key and decrypted with whichever key encrypted them. After a
 * rotation, tokens that an earlier key encrypted can be encrypted again with the primary one, so that the earlier key
 * can leave the keyset.
 */
@Service
public class CryptoService {

    /** Named after the API tokens it was first used for, and kept, as tokens stored with it must decrypt. */
    private static final String ACCESS_TOKEN_CONTEXT = "nuki-api-token:";
    private static final String REFRESH_TOKEN_CONTEXT = "nuki-refresh-token:";
    private static final String LINK_PIN_CONTEXT = "link-pin:";

    private final AeadKeyset keyset;
    private final Aead aead;

    CryptoService(AeadKeyset keyset) {
        this.keyset = keyset;
        this.aead = keyset.aead();
    }

    /**
     * The id of the key that encrypts tokens from now on, as {@code ./gradlew rotateAeadKeyset} prints it.
     *
     * @return the primary key's id, an unsigned 32-bit number
     */
    public long primaryKeyId() {
        return keyset.primaryKeyId();
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

    /**
     * Encrypts an owner's access token again with the primary key, if an earlier key encrypted it.
     *
     * @param ownerId   the owner the token was encrypted for
     * @param encrypted the ciphertext from {@link #encryptAccessToken(UUID, String)}
     * @return the token encrypted with the primary key, or empty if the primary key already encrypted it
     * @throws DecryptionFailedException if the token was encrypted for another owner, was altered, or its key is no
     *                                   longer in the keyset
     */
    public Optional<byte[]> reEncryptAccessToken(UUID ownerId, byte[] encrypted) {
        return reEncrypt(encrypted, associatedData(ACCESS_TOKEN_CONTEXT, ownerId), ownerId);
    }

    /**
     * Encrypts an owner's OAuth2 refresh token again with the primary key, if an earlier key encrypted it.
     *
     * @param ownerId   the owner the token was encrypted for
     * @param encrypted the ciphertext from {@link #encryptRefreshToken(UUID, String)}
     * @return the token encrypted with the primary key, or empty if the primary key already encrypted it
     * @throws DecryptionFailedException if the token was encrypted for another owner or as an access token, was
     *                                   altered, or its key is no longer in the keyset
     */
    public Optional<byte[]> reEncryptRefreshToken(UUID ownerId, byte[] encrypted) {
        return reEncrypt(encrypted, associatedData(REFRESH_TOKEN_CONTEXT, ownerId), ownerId);
    }

    /**
     * Encrypts the PIN of one owner's access link, which the owner can read back. The owner's and the link's ids are
     * bound as associated data, so a ciphertext copied to another link, or another owner's, cannot be decrypted.
     *
     * @param ownerId the link's owner
     * @param linkId  the link
     * @param pin     the PIN in clear text
     * @return the ciphertext, to be stored in {@code access_link.pin_encrypted}
     */
    public byte[] encryptLinkPin(UUID ownerId, UUID linkId, String pin) {
        return encrypt(pin, associatedData(LINK_PIN_CONTEXT, ownerId, linkId));
    }

    /**
     * Decrypts the PIN of one owner's access link.
     *
     * @param ownerId   the link's owner
     * @param linkId    the link
     * @param encrypted the ciphertext from {@link #encryptLinkPin(UUID, UUID, String)}
     * @return the PIN in clear text
     * @throws DecryptionFailedException if the PIN was encrypted for another link, was altered, or its key is no
     *                                   longer in the keyset
     */
    public String decryptLinkPin(UUID ownerId, UUID linkId, byte[] encrypted) {
        return decrypt(encrypted, associatedData(LINK_PIN_CONTEXT, ownerId, linkId), ownerId);
    }

    /**
     * Encrypts a link's PIN again with the primary key, if an earlier key encrypted it.
     *
     * @param ownerId   the link's owner
     * @param linkId    the link
     * @param encrypted the ciphertext from {@link #encryptLinkPin(UUID, UUID, String)}
     * @return the PIN encrypted with the primary key, or empty if the primary key already encrypted it
     * @throws DecryptionFailedException if the PIN was encrypted for another link, was altered, or its key is no
     *                                   longer in the keyset
     */
    public Optional<byte[]> reEncryptLinkPin(UUID ownerId, UUID linkId, byte[] encrypted) {
        return reEncrypt(encrypted, associatedData(LINK_PIN_CONTEXT, ownerId, linkId), ownerId);
    }

    private Optional<byte[]> reEncrypt(byte[] encrypted, byte[] associatedData, UUID ownerId) {
        if (keyset.encryptedWithPrimary(encrypted)) {
            return Optional.empty();
        }
        return Optional.of(encrypt(decrypt(encrypted, associatedData, ownerId), associatedData));
    }

    private byte[] encrypt(String token, byte[] associatedData) {
        try {
            return aead.encrypt(token.getBytes(StandardCharsets.UTF_8), associatedData);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt a secret", e);
        }
    }

    private String decrypt(byte[] encrypted, byte[] associatedData, UUID ownerId) {
        try {
            return new String(aead.decrypt(encrypted, associatedData), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new DecryptionFailedException("Cannot decrypt a secret of owner " + ownerId, e);
        }
    }

    private static byte[] associatedData(String context, UUID ownerId) {
        return (context + ownerId).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] associatedData(String context, UUID ownerId, UUID linkId) {
        return (context + ownerId + ":" + linkId).getBytes(StandardCharsets.UTF_8);
    }
}
