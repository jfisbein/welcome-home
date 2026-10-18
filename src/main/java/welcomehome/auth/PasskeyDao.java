package welcomehome.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * An owner's passkeys, in Spring Security's WebAuthn tables. They have no owner_id and no Row-Level Security:
 * every query joins user_entities by the owner's email, so an owner only ever reaches their own.
 */
@Repository
class PasskeyDao {

    private final JdbcClient jdbc;

    PasskeyDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Passkey> findAll(String email) {
        return jdbc.sql("""
                        SELECT c.credential_id, c.label, c.created, c.last_used
                        FROM user_credentials c JOIN user_entities u ON u.id = c.user_entity_user_id
                        WHERE u.name = :email
                        ORDER BY c.created, c.credential_id
                        """)
                .param("email", email)
                .query(PasskeyDao::passkey)
                .list();
    }

    /** @return whether the owner has that passkey */
    boolean rename(String email, String credentialId, String label) {
        return jdbc.sql("""
                        UPDATE user_credentials c SET label = :label
                        FROM user_entities u
                        WHERE u.id = c.user_entity_user_id AND u.name = :email AND c.credential_id = :credentialId
                        """)
                .param("label", label)
                .param("email", email)
                .param("credentialId", credentialId)
                .update() == 1;
    }

    /**
     * Deletes one of the owner's passkeys, unless it is their last: an owner without passkeys could not sign
     * in. A single statement, so that two concurrent deletions cannot remove the last two.
     *
     * @return whether the passkey was deleted
     */
    boolean deleteUnlessLast(String email, String credentialId) {
        return jdbc.sql("""
                        DELETE FROM user_credentials c
                        USING user_entities u
                        WHERE u.id = c.user_entity_user_id AND u.name = :email AND c.credential_id = :credentialId
                          AND (SELECT count(*) FROM user_credentials o WHERE o.user_entity_user_id = u.id) > 1
                        """)
                .param("email", email)
                .param("credentialId", credentialId)
                .update() == 1;
    }

    // Spring Security writes created and last_used as timestamps in the server's time zone.
    private static Passkey passkey(ResultSet rs, int row) throws SQLException {
        return new Passkey(rs.getString("credential_id"), rs.getString("label"),
                localDateTime(rs.getTimestamp("created")), localDateTime(rs.getTimestamp("last_used")));
    }

    private static @Nullable LocalDateTime localDateTime(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /**
     * @param credentialId the passkey's id, base64url
     * @param label        the name the owner gave it
     * @param created      when it was created, in the server's time zone
     * @param lastUsed     when it last signed in, in the server's time zone; {@code null} if never
     */
    record Passkey(String credentialId, String label, @Nullable LocalDateTime created,
                   @Nullable LocalDateTime lastUsed) {
    }
}
