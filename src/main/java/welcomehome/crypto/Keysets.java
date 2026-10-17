package welcomehome.crypto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.Parameters;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.mac.MacConfig;

/**
 * Reads and writes cleartext Tink keysets in JSON. They are protected as files (Docker secrets), not by a KMS.
 */
final class Keysets {

    static {
        try {
            AeadConfig.register();
            MacConfig.register();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot register Tink primitives", e);
        }
    }

    private Keysets() {
    }

    static KeysetHandle read(Path file, String environmentVariable) {
        if (file == null || file.toString().isBlank()) {
            throw new IllegalStateException("No keyset configured: set " + environmentVariable);
        }
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IllegalStateException(
                    "Keyset file not found or not readable: " + file.toAbsolutePath() + " (" + environmentVariable + ")");
        }
        try {
            return TinkJsonProtoKeysetFormat.parseKeyset(Files.readString(file), InsecureSecretKeyAccess.get());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read keyset " + file.toAbsolutePath(), e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid keyset " + file.toAbsolutePath(), e);
        }
    }

    static String generateJson(Parameters parameters) throws GeneralSecurityException {
        var keyset = KeysetHandle.generateNew(parameters);
        return TinkJsonProtoKeysetFormat.serializeKeyset(keyset, InsecureSecretKeyAccess.get());
    }
}
