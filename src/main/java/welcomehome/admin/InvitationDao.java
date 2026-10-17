package welcomehome.admin;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class InvitationDao {

    private final JdbcClient jdbc;

    InvitationDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    InsertedInvitation insert(byte[] tokenHash, String note, @Nullable UUID createdBy, boolean grantsAdmin,
                              Duration validity) {
        return jdbc.sql("""
                        INSERT INTO invitation (token_hash, note, created_by, grants_admin, expires_at)
                        VALUES (:tokenHash, :note, :createdBy, :grantsAdmin, now() + make_interval(secs => :validitySeconds))
                        RETURNING id, expires_at
                        """)
                .param("tokenHash", tokenHash)
                .param("note", note)
                .param("createdBy", createdBy)
                .param("grantsAdmin", grantsAdmin)
                .param("validitySeconds", validity.toSeconds())
                .query(InsertedInvitation.class)
                .single();
    }

    Optional<RedeemableInvitation> findRedeemable(byte[] tokenHash) {
        return jdbc.sql("""
                        SELECT id, grants_admin, expires_at
                        FROM invitation
                        WHERE token_hash = :tokenHash AND used_at IS NULL AND expires_at > now()
                        """)
                .param("tokenHash", tokenHash)
                .query(RedeemableInvitation.class)
                .optional();
    }

    /** A single UPDATE: of two concurrent redemptions of the same invitation, only one changes a row. */
    Optional<RedeemableInvitation> markUsed(byte[] tokenHash) {
        return jdbc.sql("""
                        UPDATE invitation SET used_at = now()
                        WHERE token_hash = :tokenHash AND used_at IS NULL AND expires_at > now()
                        RETURNING id, grants_admin, expires_at
                        """)
                .param("tokenHash", tokenHash)
                .query(RedeemableInvitation.class)
                .optional();
    }

    record InsertedInvitation(UUID id, OffsetDateTime expiresAt) {
    }
}
