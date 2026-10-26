package welcomehome.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeysetRotatorTests {

    static final UUID OWNER = UUID.randomUUID();
    static final String API_TOKEN = "a1b2c3d4e5f6";

    @TempDir
    Path directory;

    CryptoProperties properties;

    @BeforeEach
    void generateKeysets() throws Exception {
        KeysetGenerator.generate(directory);
        properties = new CryptoProperties(
                directory.resolve(KeysetGenerator.AEAD_KEYSET_FILE_NAME),
                directory.resolve(KeysetGenerator.MAC_KEYSET_FILE_NAME));
    }

    @Test
    void tokensStoredBeforeTheRotationStillDecrypt() throws Exception {
        var before = crypto().encryptAccessToken(OWNER, API_TOKEN);

        KeysetRotator.rotate(properties.aeadKeysetFile());

        assertThat(crypto().decryptAccessToken(OWNER, before)).isEqualTo(API_TOKEN);
    }

    @Test
    void tokensStoredAfterTheRotationUseTheNewKey() throws Exception {
        var beforeRotation = crypto();

        var newKeyId = KeysetRotator.rotate(properties.aeadKeysetFile());
        var after = crypto().encryptAccessToken(OWNER, API_TOKEN);

        // Tink's ciphertext starts with a version byte and the id of the key that encrypted it.
        assertThat(ByteBuffer.wrap(after, 1, 4).getInt()).isEqualTo(newKeyId);
        assertThatThrownBy(() -> beforeRotation.decryptAccessToken(OWNER, after))
                .isInstanceOf(DecryptionFailedException.class);
    }

    @Test
    void theRotatedKeysetIsReadableOnlyByItsOwner() throws Exception {
        Files.setPosixFilePermissions(properties.aeadKeysetFile(), PosixFilePermissions.fromString("rw-r--r--"));

        KeysetRotator.rotate(properties.aeadKeysetFile());

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(properties.aeadKeysetFile())))
                .isEqualTo("rw-------");
        try (var files = Files.list(directory)) {
            assertThat(files).as("no temporary file is left").hasSize(2);
        }
    }

    @Test
    void theMacKeysetIsLeftAsItIs() throws Exception {
        var original = Files.readString(properties.macKeysetFile());

        assertThatThrownBy(() -> KeysetRotator.rotate(properties.macKeysetFile()))
                .isInstanceOf(GeneralSecurityException.class);
        assertThat(Files.readString(properties.macKeysetFile())).isEqualTo(original);
    }

    private CryptoService crypto() throws GeneralSecurityException {
        return new CryptoService(new CryptoConfiguration().aead(properties));
    }
}
