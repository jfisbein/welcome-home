package welcomehome.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.TestKeysetsConfiguration;
import welcomehome.crypto.CryptoService;
import welcomehome.crypto.TestCryptoServices;
import welcomehome.nuki.NukiStandInConfiguration;

/** A link's optional PIN: set when creating or editing, kept encrypted, read back by its owner alone. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class LinkPinTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";
    static final String FROM = "2030-11-04T00:00";
    static final String UNTIL = "2030-12-04T00:00";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    CryptoService crypto;

    @Autowired
    LinkPins pins;

    @Autowired
    LinkPinReEncryption reEncryption;

    @Value("${welcomehome.crypto.aead-keyset-file}")
    Path aeadKeysetFile;

    JdbcClient migrator;
    TestData data;
    UUID aliceId;
    UUID lockId;

    @BeforeEach
    void insertALock() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        migrator.sql("DELETE FROM owner").update();
        aliceId = data.owner(ALICE);
        lockId = data.lock(aliceId, 17, "Casa");
        data.lock(data.owner(BOB), 18, "Piso");
    }

    @Test
    void aLinkHasNoPinUnlessTheOwnerSetsOne() {
        assertThat(create("")).hasStatus(HttpStatus.FOUND);

        assertThat(hasStoredPin()).isFalse();
        assertThat(pins.isSet(aliceId, linkId())).isFalse();
        assertThat(mvc.get().uri("/locks/" + lockId + "/links").with(owner(ALICE))).hasStatusOk()
                .bodyText().doesNotContain("Pide un PIN");
    }

    @Test
    void aPinIsStoredEncryptedAndTheListSaysTheLinkAsksForOne() {
        assertThat(create("4821")).hasStatus(HttpStatus.FOUND);

        var link = linkId();
        var stored = storedPin();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("4821");
        assertThat(crypto.decryptLinkPin(aliceId, link, stored)).isEqualTo("4821");
        assertThat(pins.check(aliceId, link, "4821")).isEqualTo(LinkPins.Check.MATCHES);
        assertThat(pins.check(aliceId, link, "4822")).isEqualTo(LinkPins.Check.DOES_NOT_MATCH);
        assertThat(pins.check(aliceId, link, null)).isEqualTo(LinkPins.Check.DOES_NOT_MATCH);
        assertThat(mvc.get().uri("/locks/" + lockId + "/links").with(owner(ALICE))).hasStatusOk()
                .bodyText().contains("Pide un PIN");
    }

    @Test
    void thePinIsNotATextOfFourToEightDigitsIsRefusedAndNothingIsCreated() {
        for (var wrong : new String[] {"123", "123456789", "12a4", "12 34", "١٢٣٤"}) {
            assertThat(create(wrong)).as(wrong).hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyText().contains("aria-invalid=\"true\"").contains("de 4 a 8 cifras");
        }
        assertThat(migrator.sql("SELECT count(*) FROM access_link").query(Long.class).single()).isZero();
    }

    @Test
    void theOwnerReadsThePinWhenEditingAndOnlyThen() {
        create("4821");
        var link = linkId();

        assertThat(mvc.get().uri("/links/" + link).with(owner(ALICE))).hasStatusOk().bodyText()
                .contains("type=\"password\"").contains("value=\"4821\"");
        assertThat(mvc.get().uri("/locks/" + lockId + "/links").with(owner(ALICE))).hasStatusOk()
                .bodyText().doesNotContain("4821");
        assertThat(mvc.get().uri("/links/" + link).with(owner(BOB))).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void editingChangesRemovesAndKeepsThePin() {
        create("4821");
        var link = linkId();

        assertThat(update(ALICE, link, "5930")).hasStatus(HttpStatus.FOUND);
        assertThat(pins.check(aliceId, link, "5930")).isEqualTo(LinkPins.Check.MATCHES);

        assertThat(update(ALICE, link, "5930")).hasStatus(HttpStatus.FOUND);
        assertThat(pins.check(aliceId, link, "5930")).as("sent back as it came").isEqualTo(LinkPins.Check.MATCHES);

        assertThat(update(ALICE, link, "")).hasStatus(HttpStatus.FOUND);
        assertThat(pins.check(aliceId, link, "5930")).isEqualTo(LinkPins.Check.NOT_SET);
        assertThat(hasStoredPin()).isFalse();
    }

    @Test
    void anotherOwnerCannotChangeThePin() {
        create("4821");
        var link = linkId();

        assertThat(update(BOB, link, "0000")).hasStatus(HttpStatus.NOT_FOUND);

        assertThat(pins.check(aliceId, link, "4821")).isEqualTo(LinkPins.Check.MATCHES);
    }

    @Test
    void pinsOfTheEarlierKeyAreEncryptedWithTheNewOneWhenTheApplicationStarts() throws Exception {
        var earlierKey = TestCryptoServices.withAeadKeyset(
                aeadKeysetFile.resolveSibling(TestKeysetsConfiguration.AEAD_KEYSET_BEFORE_ROTATION));
        create("4821");
        var link = linkId();
        migrator.sql("UPDATE access_link SET pin_encrypted = ?").param(earlierKey.encryptLinkPin(aliceId, link, "4821"))
                .update();
        assertThat(keyIdOf(storedPin())).isNotEqualTo(crypto.primaryKeyId());

        reEncryption.reEncryptAll();

        assertThat(keyIdOf(storedPin())).isEqualTo(crypto.primaryKeyId());
        assertThat(pins.check(aliceId, link, "4821")).isEqualTo(LinkPins.Check.MATCHES);
        assertThat(pins.reEncrypt(aliceId)).as("nothing left to do").isZero();
    }

    /** Tink's ciphertexts start with a version byte and the key's id, four bytes. */
    private static long keyIdOf(byte[] ciphertext) {
        return ByteBuffer.wrap(ciphertext, 1, 4).getInt() & 0xFFFFFFFFL;
    }

    private MvcTestResult create(String pin) {
        return mvc.post().uri("/locks/" + lockId + "/links").with(owner(ALICE)).with(csrf())
                .param("label", "Ana").param("validFrom", FROM).param("validUntil", UNTIL).param("pin", pin)
                .exchange();
    }

    private MvcTestResult update(String email, UUID link, String pin) {
        return mvc.patch().uri("/links/" + link).with(owner(email)).with(csrf())
                .param("label", "Ana").param("validFrom", FROM).param("validUntil", UNTIL).param("pin", pin)
                .exchange();
    }

    private UUID linkId() {
        return migrator.sql("SELECT id FROM access_link").query(UUID.class).single();
    }

    private boolean hasStoredPin() {
        return migrator.sql("SELECT pin_encrypted IS NOT NULL FROM access_link").query(Boolean.class).single();
    }

    private byte[] storedPin() {
        return migrator.sql("SELECT pin_encrypted FROM access_link").query(byte[].class).single();
    }

    private static RequestPostProcessor owner(String email) {
        return user(email).roles("OWNER");
    }
}
