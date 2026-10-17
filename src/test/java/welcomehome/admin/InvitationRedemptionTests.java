package welcomehome.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;

@IntegrationTest
class InvitationRedemptionTests {

    @Autowired
    InvitationService invitations;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;

    @BeforeEach
    void emptyInvitations() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM invitation").update();
    }

    @Test
    void invitationCanBeRedeemedOnlyOnce() {
        var created = invitations.createInvitation(null, "Alice", true);
        var token = tokenOf(created);

        assertThat(invitations.findRedeemable(token)).hasValueSatisfying(found -> {
            assertThat(found.id()).isEqualTo(created.id());
            assertThat(found.grantsAdmin()).isTrue();
        });
        assertThat(invitations.redeem(token)).map(RedeemableInvitation::id).contains(created.id());

        assertThat(invitations.findRedeemable(token)).isEmpty();
        assertThat(invitations.redeem(token)).isEmpty();
    }

    @Test
    void lookingUpAnInvitationDoesNotUseIt() {
        var token = tokenOf(invitations.createInvitation(null, "Alice", false));

        invitations.findRedeemable(token);

        assertThat(invitations.redeem(token)).isPresent();
    }

    @Test
    void expiredInvitationCannotBeRedeemed() {
        var created = invitations.createInvitation(null, "Alice", false);
        migrator.sql("UPDATE invitation SET expires_at = now() - interval '1 minute' WHERE id = ?")
                .param(created.id())
                .update();

        assertThat(invitations.findRedeemable(tokenOf(created))).isEmpty();
        assertThat(invitations.redeem(tokenOf(created))).isEmpty();
    }

    @Test
    void unknownOrMalformedTokensRedeemNothing() {
        invitations.createInvitation(null, "Alice", false);

        for (var token : new String[] {"", "unknown", "A".repeat(43), "A".repeat(1000)}) {
            assertThat(invitations.findRedeemable(token)).as(token).isEmpty();
            assertThat(invitations.redeem(token)).as(token).isEmpty();
        }
        assertThat(invitations.redeem(null)).isEmpty();
    }

    @Test
    void failedRegistrationLeavesTheInvitationUsable() {
        var token = tokenOf(invitations.createInvitation(null, "Alice", false));

        assertThatThrownBy(() -> transaction.executeWithoutResult(_ -> {
            assertThat(invitations.redeem(token)).isPresent();
            throw new IllegalStateException("registration failed after redeeming");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(invitations.redeem(token)).isPresent();
    }

    @Test
    void concurrentRedemptionsUseTheInvitationOnce() throws Exception {
        var token = tokenOf(invitations.createInvitation(null, "Alice", false));
        Callable<Optional<RedeemableInvitation>> redeem = () -> invitations.redeem(token);

        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = executor.invokeAll(Collections.nCopies(8, redeem));

            var redeemed = 0;
            for (Future<Optional<RedeemableInvitation>> result : results) {
                redeemed += result.get().isPresent() ? 1 : 0;
            }
            assertThat(redeemed).isEqualTo(1);
        }
    }

    private static String tokenOf(CreatedInvitation invitation) {
        return invitation.url().getPath().substring("/invite/".length());
    }
}
