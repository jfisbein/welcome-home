package welcomehome.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;

import com.google.crypto.tink.Parameters;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import com.google.crypto.tink.mac.PredefinedMacParameters;

/**
 * Generates the two keysets the application needs: AES-256-GCM for Nuki API tokens and HMAC-SHA256 with a
 * 128-bit tag for access links. Run it with {@code ./gradlew generateKeysets}.
 * <p>
 * Existing files are never overwritten: losing a keyset means pasting every Nuki API token again and
 * regenerating every access link.
 */
public final class KeysetGenerator {

    /** File name of the AEAD keyset inside the target directory. */
    public static final String AEAD_KEYSET_FILE_NAME = "aead-keyset.json";

    /** File name of the MAC keyset inside the target directory. */
    public static final String MAC_KEYSET_FILE_NAME = "mac-keyset.json";

    private KeysetGenerator() {
    }

    /**
     * Generates both keysets into a directory.
     *
     * @param args the target directory; {@code secrets} when absent
     * @throws IOException              if a file already exists or cannot be written
     * @throws GeneralSecurityException if Tink cannot generate a key
     */
    public static void main(String[] args) throws IOException, GeneralSecurityException {
        var directory = Path.of(args.length > 0 ? args[0] : "secrets");
        generate(directory);
        System.out.println("Keysets written to " + directory.toAbsolutePath());
    }

    /**
     * Generates both keysets into a directory, readable only by the current user.
     *
     * @param directory the target directory, created if it does not exist
     * @throws IOException              if a file already exists or cannot be written
     * @throws GeneralSecurityException if Tink cannot generate a key
     */
    public static void generate(Path directory) throws IOException, GeneralSecurityException {
        Files.createDirectories(directory);
        write(directory.resolve(AEAD_KEYSET_FILE_NAME), PredefinedAeadParameters.AES256_GCM);
        write(directory.resolve(MAC_KEYSET_FILE_NAME), PredefinedMacParameters.HMAC_SHA256_128BITTAG);
    }

    private static void write(Path file, Parameters parameters) throws IOException, GeneralSecurityException {
        var json = Keysets.generateJson(parameters);
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, json);
    }
}
