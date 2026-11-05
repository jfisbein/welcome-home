package welcomehome.crypto;

import java.security.GeneralSecurityException;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.aead.AeadKey;
import com.google.crypto.tink.util.Bytes;

/**
 * The AEAD keyset that encrypts Nuki tokens: its primitive, which encrypts with the primary key and decrypts with
 * any key in the keyset, and the primary key, which tells the tokens that another key encrypted.
 *
 * @param aead          the keyset's primitive
 * @param primaryKeyId  the primary key's id, as Tink stores it: an unsigned 32-bit number
 * @param primaryPrefix what every ciphertext of the primary key starts with: a version byte and the key's id
 */
record AeadKeyset(Aead aead, long primaryKeyId, Bytes primaryPrefix) {

    static AeadKeyset of(KeysetHandle keyset) throws GeneralSecurityException {
        // Fails unless every key is an AEAD key.
        var aead = keyset.getPrimitive(RegistryConfiguration.get(), Aead.class);
        var primary = keyset.getPrimary();
        if (!(primary.getKey() instanceof AeadKey key)) {
            throw new GeneralSecurityException("The primary key is not an AEAD key");
        }
        return new AeadKeyset(aead, Integer.toUnsignedLong(primary.getId()), key.getOutputPrefix());
    }

    /** Whether the primary key encrypted the ciphertext. */
    boolean encryptedWithPrimary(byte[] ciphertext) {
        return ciphertext.length >= primaryPrefix.size()
                && Bytes.copyFrom(ciphertext, 0, primaryPrefix.size()).equals(primaryPrefix);
    }
}
