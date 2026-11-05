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
    static final String REFRESH_TOKEN = "f6e5d4c3b2a1";

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
    void tokensOfTheEarlierKeyAreEncryptedAgainWithTheNewOne() throws Exception {
        var beforeRotation = crypto();
        var accessToken = beforeRotation.encryptAccessToken(OWNER, API_TOKEN);
        var refreshToken = beforeRotation.encryptRefreshToken(OWNER, REFRESH_TOKEN);

        var newKeyId = KeysetRotator.rotate(properties.aeadKeysetFile());
        var afterRotation = crypto();
        var accessTokenAgain = afterRotation.reEncryptAccessToken(OWNER, accessToken).orElseThrow();
        var refreshTokenAgain = afterRotation.reEncryptRefreshToken(OWNER, refreshToken).orElseThrow();

        assertThat(afterRotation.primaryKeyId()).isEqualTo(Integer.toUnsignedLong(newKeyId));
        assertThat(keyIdOf(accessTokenAgain)).isEqualTo(newKeyId);
        assertThat(keyIdOf(refreshTokenAgain)).isEqualTo(newKeyId);
        assertThat(afterRotation.decryptAccessToken(OWNER, accessTokenAgain)).isEqualTo(API_TOKEN);
        assertThat(afterRotation.decryptRefreshToken(OWNER, refreshTokenAgain)).isEqualTo(REFRESH_TOKEN);
        assertThatThrownBy(() -> afterRotation.decryptAccessToken(OWNER, refreshTokenAgain))
                .as("a refresh token stays one").isInstanceOf(DecryptionFailedException.class);
    }

    @Test
    void tokensOfTheNewKeyAreLeftAsTheyAre() throws Exception {
        KeysetRotator.rotate(properties.aeadKeysetFile());
        var crypto = crypto();

        assertThat(crypto.reEncryptAccessToken(OWNER, crypto.encryptAccessToken(OWNER, API_TOKEN))).isEmpty();
        assertThat(crypto.reEncryptRefreshToken(OWNER, crypto.encryptRefreshToken(OWNER, REFRESH_TOKEN))).isEmpty();
    }

    @Test
    void aTokenWhoseKeyLeftTheKeysetCannotBeEncryptedAgain() throws Exception {
        var elsewhere = Files.createDirectory(directory.resolve("elsewhere"));
        KeysetGenerator.generate(elsewhere);
        var token = TestCryptoServices.withAeadKeyset(elsewhere.resolve(KeysetGenerator.AEAD_KEYSET_FILE_NAME))
                .encryptAccessToken(OWNER, API_TOKEN);

        assertThatThrownBy(() -> crypto().reEncryptAccessToken(OWNER, token))
                .isInstanceOf(DecryptionFailedException.class);
    }

    @Test
    void anotherOwnersTokenIsNotEncryptedAgainForThisOwner() throws Exception {
        var token = crypto().encryptAccessToken(UUID.randomUUID(), API_TOKEN);
        KeysetRotator.rotate(properties.aeadKeysetFile());

        assertThatThrownBy(() -> crypto().reEncryptAccessToken(OWNER, token))
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

    /** Tink's ciphertext starts with a version byte and the id of the key that encrypted it. */
    private static int keyIdOf(byte[] ciphertext) {
        return ByteBuffer.wrap(ciphertext, 1, 4).getInt();
    }

    private CryptoService crypto() throws GeneralSecurityException {
        return new CryptoService(new CryptoConfiguration().aead(properties));
    }
}
