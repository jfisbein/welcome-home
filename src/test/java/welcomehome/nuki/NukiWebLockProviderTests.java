package welcomehome.nuki;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.nuki.LockProviderException.Reason;

/** Same configuration as NukiConnectionTests, so both share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class NukiWebLockProviderTests {

    static final String ALICES_TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static final String BOBS_TOKEN = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Autowired
    LockProvider locks;

    @Autowired
    NukiConnections connections;

    @Autowired
    WireMockServer nuki;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID alice;
    UUID bob;

    @BeforeEach
    void insertAliceAndBob() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        alice = insertOwner("alice@example.com");
        bob = insertOwner("bob@example.com");
    }

    @Test
    void callsNukiWithEachOwnersOwnToken() {
        connectWith(alice, ALICES_TOKEN);
        connectWith(bob, BOBS_TOKEN);
        nuki.stubFor(post("/smartlock/17/action").willReturn(status(204)));

        assertThat(locks.listLocks(bob)).containsExactly(new SmartLock(17, "Casa"));
        locks.unlatch(alice, 17);

        nuki.verify(1, postRequestedFor(urlPathEqualTo("/smartlock/17/action"))
                .withHeader("Authorization", equalTo("Bearer " + ALICES_TOKEN)));
    }

    @Test
    void ownerWithoutConnectionIsNotConnected() {
        assertNotConnected(alice);
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void disconnectedOwnerIsNotConnected() {
        connectWith(alice, ALICES_TOKEN);
        migrator.sql("UPDATE nuki_connection SET status = 'DISCONNECTED'").update();
        nuki.resetRequests();

        assertNotConnected(alice);
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void tokenCopiedFromAnotherOwnersRowIsNotUsed() {
        connectWith(alice, ALICES_TOKEN);
        connectWith(bob, BOBS_TOKEN);
        migrator.sql("""
                        UPDATE nuki_connection SET api_token_encrypted =
                            (SELECT api_token_encrypted FROM nuki_connection WHERE owner_id = ?)
                        WHERE owner_id = ?
                        """)
                .params(bob, alice)
                .update();
        nuki.resetRequests();

        assertNotConnected(alice);
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    private void connectWith(UUID ownerId, String token) {
        nuki.stubFor(get("/smartlock").withHeader("Authorization", equalTo("Bearer " + token))
                .willReturn(okJson("[{\"smartlockId\": 17, \"type\": 5, \"name\": \"Casa\", \"serverState\": 0}]")));
        connections.connect(ownerId, token);
    }

    private void assertNotConnected(UUID ownerId) {
        assertThatThrownBy(() -> locks.unlatch(ownerId, 17))
                .isInstanceOfSatisfying(LockProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONNECTED));
    }

    private UUID insertOwner(String email) {
        return migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id").param(email).query(UUID.class).single();
    }
}
