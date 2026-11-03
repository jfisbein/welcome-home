package welcomehome.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.SimplePropertyRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class InvitationDao {

    private final JdbcClient jdbc;

    InvitationDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    InsertedInvitation insert(byte[] tokenHash, String note, @Nullable UUID createdBy, boolean grantsAdmin,
                              @Nullable UUID recoversOwnerId, Duration validity) {
        return jdbc.sql("""
                        INSERT INTO invitation (token_hash, note, created_by, grants_admin, recovers_owner_id, expires_at)
                        VALUES (:tokenHash, :note, :createdBy, :grantsAdmin, :recoversOwnerId,
                                now() + make_interval(secs => :validitySeconds))
                        RETURNING id, expires_at
                        """)
                .param("tokenHash", tokenHash)
                .param("note", note)
                .param("createdBy", createdBy)
                .param("grantsAdmin", grantsAdmin)
                .param("recoversOwnerId", recoversOwnerId)
                .param("validitySeconds", validity.toSeconds())
                .query(InsertedInvitation.class)
                .single();
    }

    /** @return how many invitations, used or not, expired before {@code cutoff} and were deleted */
    int deleteExpiredBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM invitation WHERE expires_at < :cutoff")
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }

    Optional<RedeemableInvitation> findRedeemable(byte[] tokenHash) {
        return jdbc.sql("""
                        SELECT id, grants_admin, recovers_owner_id, expires_at
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
                        RETURNING id, grants_admin, recovers_owner_id, expires_at
                        """)
                .param("tokenHash", tokenHash)
                .query(RedeemableInvitation.class)
                .optional();
    }

    /** The links that could still be used, the soonest to expire first. */
    List<PendingLink> findPending() {
        return jdbc.sql("""
                        SELECT i.id, coalesce(o.email, i.note, '') AS recipient, i.grants_admin,
                               i.recovers_owner_id IS NOT NULL AS recovery, i.expires_at::timestamp AS expires_at
                        FROM invitation i LEFT JOIN owner o ON o.id = i.recovers_owner_id
                        WHERE i.used_at IS NULL AND i.expires_at > now()
                        ORDER BY i.expires_at, i.id
                        """)
                .query(new SimplePropertyRowMapper<>(PendingLink.class))
                .list();
    }

    /**
     * Deletes a link that could still be used.
     *
     * @return whom it was for, or empty if there is no such link, or it was used or expired meanwhile
     */
    Optional<String> deletePending(UUID id) {
        return jdbc.sql("""
                        WITH deleted AS (
                            DELETE FROM invitation WHERE id = :id AND used_at IS NULL AND expires_at > now()
                            RETURNING note, recovers_owner_id
                        )
                        SELECT coalesce(o.email, d.note, '')
                        FROM deleted d LEFT JOIN owner o ON o.id = d.recovers_owner_id
                        """)
                .param("id", id)
                .query(String.class)
                .optional();
    }

    record InsertedInvitation(UUID id, OffsetDateTime expiresAt) {
    }
}
