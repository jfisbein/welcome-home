package welcomehome.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.aead.PredefinedAeadParameters;

/**
 * Rotates the AEAD keyset: adds a new AES-256-GCM key and makes it the primary one. Nuki API tokens stored from
 * then on are encrypted with it, while the earlier keys stay in the keyset to decrypt the tokens stored before.
 * Run it with {@code ./gradlew rotateAeadKeyset --args=<keyset file>}.
 */
public final class KeysetRotator {

    private KeysetRotator() {
    }

    /**
     * Rotates the AEAD keyset in a file.
     *
     * @param args the keyset file
     * @throws IOException              if the file cannot be read or written
     * @throws GeneralSecurityException if the file does not hold an AEAD keyset
     */
    public static void main(String[] args) throws IOException, GeneralSecurityException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ./gradlew rotateAeadKeyset --args=<keyset file>");
        }
        var file = Path.of(args[0]);
        var keyId = rotate(file);
        System.out.println("New primary key " + Integer.toUnsignedString(keyId) + " in " + file.toAbsolutePath());
    }

    /**
     * Adds a new primary key to the AEAD keyset in a file, which is then readable only by the current user.
     *
     * @param file the keyset file
     * @return the new primary key's id, as Tink stores it: an unsigned 32-bit number
     * @throws IOException              if the file cannot be read or written
     * @throws GeneralSecurityException if the file does not hold an AEAD keyset
     */
    public static int rotate(Path file) throws IOException, GeneralSecurityException {
        var keyset = Keysets.parse(Files.readString(file));
        // Fails unless every key is an AEAD key: the MAC keyset is not rotated this way.
        keyset.getPrimitive(RegistryConfiguration.get(), Aead.class);
        var rotated = KeysetHandle.newBuilder(keyset)
                .addEntry(KeysetHandle.generateEntryFromParameters(PredefinedAeadParameters.AES256_GCM)
                        .withRandomId()
                        .makePrimary())
                .build();

        // Written next to the file and moved over it, so the keyset is never left half written.
        var temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".keyset-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(temporary, Keysets.toJson(rotated));
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return rotated.getPrimary().getId();
    }
}
