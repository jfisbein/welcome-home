package welcomehome.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CryptoServiceTests {

    static final UUID ALICE = UUID.randomUUID();
    static final UUID BOB = UUID.randomUUID();
    static final String API_TOKEN = "a1b2c3d4e5f6";

    static CryptoService crypto;

    @BeforeAll
    static void createService() throws Exception {
        crypto = new CryptoService(TestPrimitives.aead());
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
