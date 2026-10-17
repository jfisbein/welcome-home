package welcomehome.admin;

import java.time.Duration;
import java.time.OffsetDateTime;
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

    record InsertedInvitation(UUID id, OffsetDateTime expiresAt) {
    }
}
