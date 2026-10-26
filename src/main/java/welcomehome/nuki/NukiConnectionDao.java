package welcomehome.nuki;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * nuki_connection has Row-Level Security: call it inside {@code OwnerTransactions} for the same owner.
 * Every statement also filters by owner_id, so it never depends on RLS alone.
 */
@Repository
class NukiConnectionDao {

    private final JdbcClient jdbc;

    NukiConnectionDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<StoredConnection> find(UUID ownerId) {
        return jdbc.sql("""
                        SELECT id, credential, access_token_encrypted, refresh_token_encrypted,
                               access_token_expires_at, status, last_checked_at::timestamp AS last_checked_at
                        FROM nuki_connection
                        WHERE owner_id = :ownerId
                        """)
                .param("ownerId", ownerId)
                .query(NukiConnectionDao::storedConnection)
                .optional();
    }

    /**
     * Stores a connection whose token Nuki has just accepted, replacing any earlier one.
     *
     * @param refreshTokenEncrypted  the refresh token, for {@link NukiCredential#OAUTH2} only
     * @param accessTokenExpiresAt   when the access token expires, for {@link NukiCredential#OAUTH2} only
     */
    void save(UUID ownerId, NukiCredential credential, byte[] accessTokenEncrypted,
              byte @Nullable [] refreshTokenEncrypted, @Nullable Instant accessTokenExpiresAt) {
        jdbc.sql("""
                        INSERT INTO nuki_connection (owner_id, credential, access_token_encrypted,
                                                     refresh_token_encrypted, access_token_expires_at, status,
                                                     last_checked_at)
                        VALUES (:ownerId, :credential, :accessTokenEncrypted, :refreshTokenEncrypted,
                                :accessTokenExpiresAt, 'OK', now())
                        ON CONFLICT (owner_id) DO UPDATE
                        SET credential = excluded.credential,
                            access_token_encrypted = excluded.access_token_encrypted,
                            refresh_token_encrypted = excluded.refresh_token_encrypted,
                            access_token_expires_at = excluded.access_token_expires_at,
                            status = 'OK', last_checked_at = now()
                        """)
                .param("ownerId", ownerId)
                .param("credential", credential.name())
                .param("accessTokenEncrypted", accessTokenEncrypted)
                .param("refreshTokenEncrypted", refreshTokenEncrypted)
                .param("accessTokenExpiresAt", timestamp(accessTokenExpiresAt))
                .update();
    }

    /**
     * Stores the tokens that a refresh returned, if the connection still holds the access token that was
     * refreshed: one stored in the meantime, by connecting Nuki again, stays.
     *
     * @return whether the tokens were stored
     */
    boolean saveRefreshed(UUID ownerId, byte[] refreshedAccessTokenEncrypted, byte[] accessTokenEncrypted,
                          byte[] refreshTokenEncrypted, Instant accessTokenExpiresAt) {
        return jdbc.sql("""
                        UPDATE nuki_connection
                        SET access_token_encrypted = :accessTokenEncrypted,
                            refresh_token_encrypted = :refreshTokenEncrypted,
                            access_token_expires_at = :accessTokenExpiresAt
                        WHERE owner_id = :ownerId AND credential = 'OAUTH2'
                          AND access_token_encrypted = :refreshed AND status = 'OK'
                        """)
                .param("ownerId", ownerId)
                .param("refreshed", refreshedAccessTokenEncrypted)
                .param("accessTokenEncrypted", accessTokenEncrypted)
                .param("refreshTokenEncrypted", refreshTokenEncrypted)
                .param("accessTokenExpiresAt", timestamp(accessTokenExpiresAt))
                .update() == 1;
    }

    /**
     * Disconnects the owner's connection if it still holds the given access token and is still connected: a
     * token stored while the rejected one was in use stays connected.
     *
     * @return whether the connection went from OK to disconnected
     */
    boolean markDisconnected(UUID ownerId, byte[] rejectedAccessTokenEncrypted) {
        return jdbc.sql("""
                        UPDATE nuki_connection SET status = 'DISCONNECTED', last_checked_at = now()
                        WHERE owner_id = :ownerId AND access_token_encrypted = :rejected AND status = 'OK'
                        """)
                .param("ownerId", ownerId)
                .param("rejected", rejectedAccessTokenEncrypted)
                .update() == 1;
    }

    /**
     * Locks keep their row: their connection_id becomes NULL, and their links do not change.
     *
     * @return how the deleted connection was made, or empty if the owner had none
     */
    Optional<NukiCredential> delete(UUID ownerId) {
        return jdbc.sql("DELETE FROM nuki_connection WHERE owner_id = :ownerId RETURNING credential")
                .param("ownerId", ownerId)
                .query((rs, row) -> NukiCredential.valueOf(rs.getString("credential")))
                .optional();
    }

    void markChecked(UUID ownerId) {
        jdbc.sql("UPDATE nuki_connection SET last_checked_at = now() WHERE owner_id = :ownerId AND status = 'OK'")
                .param("ownerId", ownerId)
                .update();
    }

    /** From owner, which has no Row-Level Security: without an owner context nuki_connection shows no rows. */
    List<UUID> allOwnerIds() {
        return jdbc.sql("SELECT id FROM owner ORDER BY created_at")
                .query((rs, row) -> rs.getObject("id", UUID.class))
                .list();
    }

    private static @Nullable OffsetDateTime timestamp(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static StoredConnection storedConnection(ResultSet rs, int row) throws SQLException {
        var expiresAt = rs.getObject("access_token_expires_at", OffsetDateTime.class);
        return new StoredConnection(rs.getObject("id", UUID.class),
                NukiCredential.valueOf(rs.getString("credential")),
                rs.getBytes("access_token_encrypted"),
                rs.getBytes("refresh_token_encrypted"),
                expiresAt == null ? null : expiresAt.toInstant(),
                ConnectionStatus.valueOf(rs.getString("status")),
                rs.getObject("last_checked_at", LocalDateTime.class));
    }

    /**
     * A connection as stored.
     *
     * @param accessTokenExpiresAt when an OAuth2 access token expires; null for an API token
     */
    // Never compared, so the arrays' identity-based equals does not matter.
    @SuppressWarnings("ArrayRecordComponent")
    record StoredConnection(UUID id, NukiCredential credential, byte[] accessTokenEncrypted,
                            byte @Nullable [] refreshTokenEncrypted, @Nullable Instant accessTokenExpiresAt,
                            ConnectionStatus status, @Nullable LocalDateTime lastCheckedAt) {
    }
}
