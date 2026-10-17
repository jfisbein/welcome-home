package welcomehome.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;

import com.google.crypto.tink.Aead;
import org.springframework.stereotype.Service;

/**
 * Encrypts each owner's Nuki API token with AEAD (AES-256-GCM). The owner's id is bound as associated data,
 * so a ciphertext copied into another owner's row cannot be decrypted.
 */
@Service
public class CryptoService {

    private static final String API_TOKEN_CONTEXT = "nuki-api-token:";

    private final Aead aead;

    CryptoService(Aead aead) {
        this.aead = aead;
    }

    /**
     * Encrypts a Nuki API token for one owner.
     *
     * @param ownerId  the owner the token belongs to
     * @param apiToken the token in clear text
     * @return the ciphertext, to be stored in {@code nuki_connection.api_token_encrypted}
     */
    public byte[] encryptApiToken(UUID ownerId, String apiToken) {
        try {
            return aead.encrypt(apiToken.getBytes(StandardCharsets.UTF_8), associatedData(ownerId));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt the Nuki API token", e);
        }
    }

    /**
     * Decrypts the Nuki API token of one owner.
     *
     * @param ownerId   the owner the token was encrypted for
     * @param encrypted the ciphertext from {@link #encryptApiToken(UUID, String)}
     * @return the token in clear text
     * @throws DecryptionFailedException if the token was encrypted for another owner, was altered, or its key
     *                                   is no longer in the keyset
     */
    public String decryptApiToken(UUID ownerId, byte[] encrypted) {
        try {
            return new String(aead.decrypt(encrypted, associatedData(ownerId)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new DecryptionFailedException("Cannot decrypt the Nuki API token of owner " + ownerId, e);
        }
    }

    private static byte[] associatedData(UUID ownerId) {
        return (API_TOKEN_CONTEXT + ownerId).getBytes(StandardCharsets.UTF_8);
    }
}
