package welcomehome.crypto;

import java.nio.file.Files;
import java.security.GeneralSecurityException;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.Mac;

/**
 * Tink primitives from keysets generated once per test run, for unit tests of the crypto module.
 */
final class TestPrimitives {

    private static final CryptoProperties PROPERTIES = generateKeysets();

    private TestPrimitives() {
    }

    static Aead aead() throws GeneralSecurityException {
        return new CryptoConfiguration().aead(PROPERTIES);
    }

    static Mac mac() throws GeneralSecurityException {
        return new CryptoConfiguration().mac(PROPERTIES);
    }

    private static CryptoProperties generateKeysets() {
        try {
            var directory = Files.createTempDirectory("welcome-home-keysets");
            KeysetGenerator.generate(directory);
            var properties = new CryptoProperties(
                    directory.resolve(KeysetGenerator.AEAD_KEYSET_FILE_NAME),
                    directory.resolve(KeysetGenerator.MAC_KEYSET_FILE_NAME));
            directory.toFile().deleteOnExit();
            properties.aeadKeysetFile().toFile().deleteOnExit();
            properties.macKeysetFile().toFile().deleteOnExit();
            return properties;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot generate test keysets", e);
        }
    }
}
