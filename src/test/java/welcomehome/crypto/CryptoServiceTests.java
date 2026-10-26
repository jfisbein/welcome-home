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
    static final String REFRESH_TOKEN = "f6e5d4c3b2a1";

    static CryptoService crypto;

    @BeforeAll
    static void createService() throws Exception {
        crypto = new CryptoService(TestPrimitives.aead());
    }

    @Test
    void decryptsWhatItEncryptedForTheSameOwner() {
        var encrypted = crypto.encryptAccessToken(ALICE, API_TOKEN);

        assertThat(crypto.decryptAccessToken(ALICE, encrypted)).isEqualTo(API_TOKEN);
    }

    @Test
    void anotherOwnerCannotDecryptTheToken() {
        var encrypted = crypto.encryptAccessToken(ALICE, API_TOKEN);

        assertThatThrownBy(() -> crypto.decryptAccessToken(BOB, encrypted))
                .isInstanceOf(DecryptionFailedException.class);
    }

    @Test
    void alteredCiphertextIsRejected() {
        var encrypted = crypto.encryptAccessToken(ALICE, API_TOKEN);
        encrypted[encrypted.length - 1] ^= 1;

        assertThatThrownBy(() -> crypto.decryptAccessToken(ALICE, encrypted))
                .isInstanceOf(DecryptionFailedException.class);
    }

    @Test
    void refreshTokenDecryptsForTheSameOwnerOnly() {
        var encrypted = crypto.encryptRefreshToken(ALICE, REFRESH_TOKEN);

        assertThat(crypto.decryptRefreshToken(ALICE, encrypted)).isEqualTo(REFRESH_TOKEN);
        assertThatThrownBy(() -> crypto.decryptRefreshToken(BOB, encrypted))
                .isInstanceOf(DecryptionFailedException.class);
    }

    @Test
    void neitherKindOfTokenStandsInForTheOther() {
        var refreshToken = crypto.encryptRefreshToken(ALICE, REFRESH_TOKEN);
        var accessToken = crypto.encryptAccessToken(ALICE, API_TOKEN);

        assertThatThrownBy(() -> crypto.decryptAccessToken(ALICE, refreshToken))
                .isInstanceOf(DecryptionFailedException.class);
        assertThatThrownBy(() -> crypto.decryptRefreshToken(ALICE, accessToken))
                .isInstanceOf(DecryptionFailedException.class);
    }
}
