package welcomehome.crypto;

import java.nio.file.Path;
import java.security.GeneralSecurityException;

/**
 * Encryption with an AEAD keyset other than the application's, for tests outside this package that need tokens
 * of an earlier key, or of a key the application does not have.
 */
public final class TestCryptoServices {

    private TestCryptoServices() {
    }

    /**
     * Encrypts and decrypts Nuki tokens as the application does, with the AEAD keyset in a file.
     *
     * @param aeadKeysetFile the AEAD keyset
     * @return the service
     * @throws GeneralSecurityException if the file does not hold an AEAD keyset
     */
    public static CryptoService withAeadKeyset(Path aeadKeysetFile) throws GeneralSecurityException {
        return new CryptoService(new CryptoConfiguration().aead(new CryptoProperties(aeadKeysetFile, aeadKeysetFile)));
    }
}
