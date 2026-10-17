package welcomehome.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CryptoServiceTests {

    static final UUID ALICE = UUID.randomUUID();
    static final UUID BOB = UUID.randomUUID();
    static final String API_TOKEN = "a1b2c3d4e5f6";

    @TempDir
    static Path keysets;

    static CryptoService crypto;

    @BeforeAll
    static void createService() throws Exception {
        KeysetGenerator.generate(keysets);
        var properties = new CryptoProperties(
                keysets.resolve(KeysetGenerator.AEAD_KEYSET_FILE_NAME),
                keysets.resolve(KeysetGenerator.MAC_KEYSET_FILE_NAME));
        crypto = new CryptoService(new CryptoConfiguration().aead(properties));
    }

    @Test
    void decryptsWhatItEncryptedForTheSameOwner() {
        var encrypted = crypto.encryptApiToken(ALICE, API_TOKEN);

        assertThat(crypto.decryptApiToken(ALICE, encrypted)).isEqualTo(API_TOKEN);
    }

    @Test
    void anotherOwnerCannotDecryptTheToken() {
        var encrypted = crypto.encryptApiToken(ALICE, API_TOKEN);

        assertThatThrownBy(() -> crypto.decryptApiToken(BOB, encrypted))
                .isInstanceOf(DecryptionFailedException.class);
    }

    @Test
    void alteredCiphertextIsRejected() {
        var encrypted = crypto.encryptApiToken(ALICE, API_TOKEN);
        encrypted[encrypted.length - 1] ^= 1;

        assertThatThrownBy(() -> crypto.decryptApiToken(ALICE, encrypted))
                .isInstanceOf(DecryptionFailedException.class);
    }
}
