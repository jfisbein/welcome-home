package welcomehome.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;

@IntegrationTest
class PasskeyRecoveryCommandTests {

    @Autowired
    PasskeyRecoveryCommand command;

    @Autowired
    InvitationService invitations;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    UUID adminId;

    @BeforeEach
    void insertAnAdmin() {
        var migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM invitation").update();
        migrator.sql("DELETE FROM owner").update();
        adminId = migrator.sql("INSERT INTO owner (email, is_admin) VALUES ('admin@example.com', true) RETURNING id")
                .query(UUID.class)
                .single();
    }

    @Test
    void createsARecoveryLinkForTheOwnerWithThatEmail() {
        var recovery = command.createRecovery(" Admin@Example.com ").orElseThrow();

        assertThat(recovery.url().toString()).matches(IntegrationTest.BASE_URL + "/invite/[A-Za-z0-9_-]{43}");
        var token = recovery.url().getPath().substring("/invite/".length());
        assertThat(invitations.findRedeemable(token)).hasValueSatisfying(invitation -> {
            assertThat(invitation.recoversOwnerId()).isEqualTo(adminId);
            assertThat(invitation.grantsAdmin()).isFalse();
        });
        assertThat(jdbc.sql("SELECT created_by FROM invitation").query(UUID.class).optional()).isEmpty();
    }

    @Test
    void createsNothingForAnUnknownEmail() {
        assertThat(command.createRecovery("nobody@example.com")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isZero();
    }
}
