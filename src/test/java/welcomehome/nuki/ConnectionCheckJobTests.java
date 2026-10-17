package welcomehome.nuki;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;

/** Same configuration as NukiConnectionTests, so both share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class ConnectionCheckJobTests {

    static final String LONG_AGO = "2000-01-01 00:00:00+00";

    @Autowired
    ConnectionCheckJob job;

    @Autowired
    NukiConnections connections;

    @Autowired
    WireMockServer nuki;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;

    @BeforeEach
    void emptyDatabase() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
    }

    @Test
    void dailyCheckDisconnectsOnlyOwnersWhoseTokenNukiRejects() {
        var valid = connectedOwner("valid", okJson("[]"));
        var revoked = connectedOwner("revoked", status(401));
        var nukiDown = connectedOwner("down", status(503));
        var alreadyDisconnected = connectedOwner("disconnected", okJson("[]"));
        migrator.sql("UPDATE nuki_connection SET status = 'DISCONNECTED' WHERE owner_id = ?")
                .param(alreadyDisconnected).update();
        migrator.sql("INSERT INTO owner (email) VALUES ('never-connected@example.com')").update();
        migrator.sql("UPDATE nuki_connection SET last_checked_at = '" + LONG_AGO + "'").update();
        nuki.resetRequests();

        job.checkAll();

        assertThat(connectionStatus(valid)).isEqualTo("OK");
        assertThat(checkedRecently(valid)).isTrue();
        assertThat(connectionStatus(revoked)).isEqualTo("DISCONNECTED");
        assertThat(checkedRecently(revoked)).isTrue();
        assertThat(connectionStatus(nukiDown)).isEqualTo("OK");
        assertThat(checkedRecently(nukiDown)).as("an unanswered check is not a check").isFalse();
        assertThat(connectionStatus(alreadyDisconnected)).isEqualTo("DISCONNECTED");
        nuki.verify(0, getRequestedFor(urlPathEqualTo("/smartlock"))
                .withHeader("Authorization", equalTo("Bearer token-disconnected")));
    }

    /** An owner connected with the token "token-{name}", for which Nuki then answers with {@code response}. */
    private UUID connectedOwner(String name, ResponseDefinitionBuilder response) {
        var ownerId = migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id")
                .param(name + "@example.com")
                .query(UUID.class)
                .single();
        var token = "token-" + name;
        nuki.stubFor(get("/smartlock").withHeader("Authorization", equalTo("Bearer " + token))
                .inScenario(name).whenScenarioStateIs("Started").willReturn(okJson("[]")).willSetStateTo("connected"));
        nuki.stubFor(get("/smartlock").withHeader("Authorization", equalTo("Bearer " + token))
                .inScenario(name).whenScenarioStateIs("connected").willReturn(response));
        connections.connect(ownerId, token);
        return ownerId;
    }

    private String connectionStatus(UUID ownerId) {
        return migrator.sql("SELECT status FROM nuki_connection WHERE owner_id = ?").param(ownerId)
                .query(String.class).single();
    }

    private boolean checkedRecently(UUID ownerId) {
        return migrator.sql("SELECT last_checked_at > now() - interval '1 minute' FROM nuki_connection WHERE owner_id = ?")
                .param(ownerId)
                .query(Boolean.class)
                .single();
    }
}
