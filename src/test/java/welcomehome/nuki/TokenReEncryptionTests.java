package welcomehome.nuki;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.TestKeysetsConfiguration;
import welcomehome.crypto.CryptoService;
import welcomehome.crypto.KeysetGenerator;
import welcomehome.crypto.TestCryptoServices;

/**
 * After the AEAD keyset is rotated, the tokens that the earlier key encrypted are encrypted with the new one when the
 * application starts, so that the earlier key can leave the keyset. The test contexts' keysets are rotated once.
 * Same configuration as the locks tests, so they share one application context.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class TokenReEncryptionTests {

    static final List<NukiDevice> CASA = List.of(new NukiDevice(17, DeviceType.SMART_LOCK, "Casa", null, null));

    @Autowired
    ConfigurableApplicationContext context;

    @Autowired
    TokenReEncryption reEncryption;

    @Autowired
    LockProvider locks;

    @Autowired
    CryptoService crypto;

    @Autowired
    WireMockServer nuki;

    @Autowired
    PostgreSQLContainer postgres;

    @Value("${welcomehome.crypto.aead-keyset-file}")
    Path aeadKeysetFile;

    @TempDir
    Path elsewhere;

    CryptoService earlierKey;
    JdbcClient migrator;
    TestData data;

    @BeforeEach
    void noOwners() throws Exception {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        migrator.sql("DELETE FROM owner").update();
        earlierKey = TestCryptoServices.withAeadKeyset(
                aeadKeysetFile.resolveSibling(TestKeysetsConfiguration.AEAD_KEYSET_BEFORE_ROTATION));
    }

    @Test
    void whenTheApplicationStartsTokensOfTheEarlierKeyAreEncryptedWithTheNewOne(CapturedOutput output) {
        var ana = data.owner("ana@example.com");
        data.apiToken(ana, earlierKey.encryptAccessToken(ana, "ana-token"));
        var bea = data.owner("bea@example.com");
        data.authorization(bea, earlierKey.encryptAccessToken(bea, "bea-access"),
                earlierKey.encryptRefreshToken(bea, "bea-refresh"), "1 hour", "DISCONNECTED");
        var cata = data.owner("cata@example.com");
        var cataToken = crypto.encryptAccessToken(cata, "cata-token");
        data.apiToken(cata, cataToken);

        context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));

        assertThat(List.of(keyIdOf(accessToken(ana)), keyIdOf(accessToken(bea)), keyIdOf(refreshToken(bea))))
                .containsOnly(crypto.primaryKeyId());
        assertThat(crypto.decryptAccessToken(ana, accessToken(ana))).isEqualTo("ana-token");
        assertThat(crypto.decryptAccessToken(bea, accessToken(bea))).isEqualTo("bea-access");
        assertThat(crypto.decryptRefreshToken(bea, refreshToken(bea))).isEqualTo("bea-refresh");
        assertThat(status(bea)).isEqualTo("DISCONNECTED");
        assertThat(accessToken(cata)).as("already the new key's").isEqualTo(cataToken);
        assertThat(output.getAll())
                .contains("Nuki tokens encrypted again with key " + crypto.primaryKeyId() + ": 3")
                .doesNotContain("ana-token", "bea-access", "bea-refresh");
    }

    @Test
    void aTokenWhoseKeyLeftTheKeysetIsLeftAsItIs(CapturedOutput output) throws Exception {
        KeysetGenerator.generate(elsewhere);
        var otherKey = TestCryptoServices.withAeadKeyset(elsewhere.resolve(KeysetGenerator.AEAD_KEYSET_FILE_NAME));
        var ana = data.owner("ana@example.com");
        var anaToken = otherKey.encryptAccessToken(ana, "ana-token");
        data.apiToken(ana, anaToken);
        var bea = data.owner("bea@example.com");
        data.apiToken(bea, earlierKey.encryptAccessToken(bea, "bea-token"));

        reEncryption.reEncryptAll();

        assertThat(accessToken(ana)).isEqualTo(anaToken);
        assertThat(crypto.decryptAccessToken(bea, accessToken(bea))).as("the others' still are").isEqualTo("bea-token");
        assertThat(keyIdOf(accessToken(bea))).isEqualTo(crypto.primaryKeyId());
        assertThat(output.getAll())
                .contains("Could not decrypt the Nuki tokens of owner " + ana + " to encrypt them with key "
                        + crypto.primaryKeyId());
    }

    /** It waits for the refresh: changing the tokens under it would lose the ones Nuki returned, which it rotates. */
    @Test
    void aRefreshUnderWayKeepsTheTokensNukiReturned() throws Exception {
        var ana = data.owner("ana@example.com");
        data.authorization(ana, earlierKey.encryptAccessToken(ana, "access-1"),
                earlierKey.encryptRefreshToken(ana, "refresh-1"), "-1 minute", "OK");
        nuki.stubFor(post("/oauth/token").withFormParam("refresh_token", equalTo("refresh-1"))
                .willReturn(okJson("""
                        {"access_token": "access-2", "token_type": "bearer", "expires_in": 3599,
                         "refresh_token": "refresh-2", "scope": "smartlock.readOnly offline_access"}
                        """).withFixedDelay(1000)));
        nuki.stubFor(get("/smartlock").withHeader("Authorization", equalTo("Bearer access-2"))
                .willReturn(okJson("[{\"smartlockId\": 17, \"type\": 5, \"name\": \"Casa\", \"serverState\": 0}]")));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var call = executor.submit(() -> locks.listLocks(ana));
            untilNukiIsAskedToRefresh();
            reEncryption.reEncryptAll();

            assertThat(call.get()).isEqualTo(CASA);
        }
        assertThat(crypto.decryptAccessToken(ana, accessToken(ana))).isEqualTo("access-2");
        assertThat(crypto.decryptRefreshToken(ana, refreshToken(ana))).isEqualTo("refresh-2");
        assertThat(keyIdOf(refreshToken(ana))).isEqualTo(crypto.primaryKeyId());
    }

    private void untilNukiIsAskedToRefresh() throws InterruptedException {
        var deadline = Instant.now().plusSeconds(5);
        while (nuki.findAll(postRequestedFor(urlPathEqualTo("/oauth/token"))).isEmpty()) {
            assertThat(Instant.now()).as("Nuki was asked to refresh").isBefore(deadline);
            Thread.sleep(10);
        }
    }

    /** Tink's ciphertext starts with a version byte and the id of the key that encrypted it. */
    private static long keyIdOf(byte[] ciphertext) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(ciphertext, 1, 4).getInt());
    }

    private byte[] accessToken(UUID owner) {
        return column(owner, "access_token_encrypted");
    }

    private byte[] refreshToken(UUID owner) {
        return column(owner, "refresh_token_encrypted");
    }

    private String status(UUID owner) {
        return migrator.sql("SELECT status FROM nuki_connection WHERE owner_id = ?").param(owner).query(String.class)
                .single();
    }

    private byte[] column(UUID owner, String column) {
        return migrator.sql("SELECT " + column + " FROM nuki_connection WHERE owner_id = ?").param(owner)
                .query(byte[].class).single();
    }
}
