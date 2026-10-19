package welcomehome.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;

@IntegrationTest
class OwnerTransactionsTests {

    static final UUID OWNER = UUID.randomUUID();
    static final UUID OTHER_OWNER = UUID.randomUUID();

    static final String SESSION_SQL = """
            SELECT pg_backend_pid() AS pid,
                   nullif(current_setting('app.owner_id', true), '') AS owner_id
            """;

    @Autowired
    OwnerTransactions ownerTransactions;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    PostgreSQLContainer postgres;

    @BeforeEach
    void createProbeTable() {
        var migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DROP TABLE IF EXISTS tx_probe").update();
        migrator.sql("CREATE TABLE tx_probe (value text NOT NULL)").update();
        migrator.sql("GRANT SELECT, INSERT ON tx_probe TO app_user").update();
    }

    /** Other tests list the schema's tables: the probe must not outlive these tests. */
    @AfterEach
    void dropProbeTable() {
        TestDatabaseConfiguration.asMigrator(postgres).sql("DROP TABLE IF EXISTS tx_probe").update();
    }

    @Test
    void ownerContextLastsOnlyForTheTransaction() {
        var inside = ownerTransactions.execute(OWNER, () -> jdbc.sql(SESSION_SQL).query(Session.class).single());
        var after = jdbc.sql(SESSION_SQL).query(Session.class).single();

        assertThat(inside.ownerId()).isEqualTo(OWNER.toString());
        assertThat(after.pid()).as("same pooled connection").isEqualTo(inside.pid());
        assertThat(after.ownerId()).isNull();
    }

    @Test
    void rollbackUndoesWhatWasWritten() {
        assertThatThrownBy(() -> ownerTransactions.run(OWNER, () -> {
            jdbc.sql("INSERT INTO tx_probe (value) VALUES ('written')").update();
            throw new IllegalStateException("fails after writing");
        })).isInstanceOf(IllegalStateException.class);

        var rows = jdbc.sql("SELECT count(*) FROM tx_probe").query(Long.class).single();
        assertThat(rows).isZero();
    }

    @Test
    void anotherOwnerCannotJoinATransactionBoundToAnOwner() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(_ -> {
            ownerTransactions.run(OWNER, () -> jdbc.sql("INSERT INTO tx_probe (value) VALUES ('owner')").update());
            ownerTransactions.run(OTHER_OWNER, () -> { });
        })).isInstanceOf(IllegalStateException.class).hasMessage("The transaction is already bound to another owner");

        assertThatThrownBy(() -> ownerTransactions.run(OWNER, () -> ownerTransactions.run(OTHER_OWNER, () -> { })))
                .isInstanceOf(IllegalStateException.class);

        var rows = jdbc.sql("SELECT count(*) FROM tx_probe").query(Long.class).single();
        assertThat(rows).as("the whole transaction rolled back").isZero();
    }

    @Test
    void theSameOwnerJoinsTheTransaction() {
        var inner = transactionTemplate.execute(_ -> {
            var outer = ownerTransactions.execute(OWNER, () -> jdbc.sql(SESSION_SQL).query(Session.class).single());
            var nested = ownerTransactions.execute(OWNER, () -> jdbc.sql(SESSION_SQL).query(Session.class).single());
            assertThat(nested).isEqualTo(outer);
            return nested;
        });

        assertThat(inner.ownerId()).isEqualTo(OWNER.toString());
    }

    record Session(int pid, String ownerId) {
    }
}
