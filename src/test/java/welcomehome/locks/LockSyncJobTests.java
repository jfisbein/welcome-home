package welcomehome.locks;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.crypto.CryptoService;
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as LockSyncTests, so both share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class LockSyncJobTests {

    static final String ONLY_CASA = """
            [{"smartlockId": 17, "type": 5, "name": "Casa", "serverState": 0}]
            """;

    @Autowired
    LockSyncJob job;

    @Autowired
    WireMockServer nuki;

    @Autowired
    CryptoService crypto;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;

    @BeforeEach
    void emptyDatabase() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        migrator.sql("DELETE FROM owner").update();
    }

    @Test
    void aLockRemovedFromNukiBecomesInactiveAndANewOneIsImported() {
        var alice = connectedOwner("alice");
        insertLock(alice, 18, "Garaje");
        nukiLists("alice", ONLY_CASA);

        job.syncAll();

        assertThat(locks(alice)).containsExactly("17|Casa|true", "18|Garaje|false");
    }

    @Test
    void anEmptyListChangesNothing() {
        var alice = connectedOwner("alice");
        insertLock(alice, 18, "Garaje");
        nukiLists("alice", "[]");

        job.syncAll();

        assertThat(locks(alice)).containsExactly("18|Garaje|true");
    }

    @Test
    void oneOwnersFailureDoesNotStopTheOthersNorChangesTheirLocks() {
        var failing = connectedOwner("failing");
        insertLock(failing, 18, "Garaje");
        nuki.stubFor(get("/smartlock").withHeader("Authorization", equalTo("Bearer token-failing"))
                .willReturn(status(503)));
        var alice = connectedOwner("alice");
        nukiLists("alice", ONLY_CASA);

        job.syncAll();

        assertThat(locks(failing)).containsExactly("18|Garaje|true");
        assertThat(locks(alice)).containsExactly("17|Casa|true");
    }

    @Test
    void anOwnerWithoutAConnectionIsLeftAlone() {
        var bob = data.owner("bob@example.com");
        insertLock(bob, 19, "Portal");

        job.syncAll();

        assertThat(locks(bob)).containsExactly("19|Portal|true");
        assertThat(nuki.getAllServeEvents()).isEmpty();
    }

    private UUID connectedOwner(String name) {
        var ownerId = data.owner(name + "@example.com");
        data.apiToken(ownerId, crypto.encryptAccessToken(ownerId, "token-" + name));
        return ownerId;
    }

    private void nukiLists(String name, String locksJson) {
        nuki.stubFor(get("/smartlock").withHeader("Authorization", equalTo("Bearer token-" + name))
                .willReturn(okJson(locksJson)));
    }

    private void insertLock(UUID ownerId, long nukiSmartlockId, String name) {
        data.lock(ownerId, nukiSmartlockId, name);
    }

    private List<String> locks(UUID ownerId) {
        return migrator.sql("""
                        SELECT concat_ws('|', nuki_smartlock_id, name, CASE WHEN active THEN 'true' ELSE 'false' END)
                        FROM lock WHERE owner_id = ? ORDER BY nuki_smartlock_id
                        """)
                .param(ownerId).query(String.class).list();
    }
}
