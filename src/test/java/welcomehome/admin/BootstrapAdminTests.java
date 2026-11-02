package welcomehome.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;

@IntegrationTest
class BootstrapAdminTests {

    @Autowired
    BootstrapAdmin bootstrapAdmin;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;

    @BeforeEach
    void emptyDatabase() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        migrator.sql("DELETE FROM invitation").update();
        migrator.sql("DELETE FROM owner").update();
    }

    @Test
    void createsAnAdminInvitationWhenThereIsNoAdmin() throws Exception {
        var invitation = bootstrapAdmin.createAdminInvitation().orElseThrow();

        assertThat(invitation.url().toString()).matches(IntegrationTest.BASE_URL + "/invite/[A-Za-z0-9_-]{43}");
        assertThat(invitation.expiresAt()).isCloseTo(Instant.now().plus(InvitationService.VALIDITY), within(1, ChronoUnit.MINUTES));

        var token = invitation.url().getPath().substring("/invite/".length());
        var stored = jdbc.sql("SELECT token_hash, created_by, grants_admin, expires_at, used_at FROM invitation WHERE id = ?")
                .param(invitation.id())
                .query(StoredInvitation.class)
                .single();
        assertThat(stored.tokenHash()).isEqualTo(sha256(token));
        assertThat(stored.createdBy()).isNull();
        assertThat(stored.grantsAdmin()).isTrue();
        assertThat(stored.expiresAt().toInstant()).isEqualTo(invitation.expiresAt());
        assertThat(stored.usedAt()).isNull();
    }

    @Test
    void refusesWhenAnAdminExists() {
        data.admin("admin@example.com");

        assertThat(bootstrapAdmin.createAdminInvitation()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isZero();
    }

    @Test
    void ownersWhoAreNotAdminsDoNotPreventTheBootstrap() {
        data.owner("owner@example.com");

        assertThat(bootstrapAdmin.createAdminInvitation()).isPresent();
    }

    private static byte[] sha256(String token) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
    }

    // Never compared, so the array's identity-based equals does not matter.
    @SuppressWarnings("ArrayRecordComponent")
    record StoredInvitation(byte[] tokenHash, @Nullable UUID createdBy, boolean grantsAdmin,
                            OffsetDateTime expiresAt, @Nullable OffsetDateTime usedAt) {
    }
}
