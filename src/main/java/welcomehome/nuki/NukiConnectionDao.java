package welcomehome.nuki;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
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
                        SELECT access_token_encrypted, refresh_token_encrypted,
                               access_token_expires_at, status, last_checked_at
                        FROM nuki_connection
                        WHERE owner_id = :ownerId
                        """)
                .param("ownerId", ownerId)
                .query(NukiConnectionDao::storedConnection)
                .optional();
    }

    /**
     * Finds the owner's connection if it was disconnected no longer ago than the given time. It is also when it was
     * last checked, as a failed check changes nothing.
     */
    Optional<StoredConnection> findDisconnectedWithin(UUID ownerId, Duration time) {
        return jdbc.sql("""
                        SELECT access_token_encrypted, refresh_token_encrypted,
                               access_token_expires_at, status, last_checked_at
                        FROM nuki_connection
                        WHERE owner_id = :ownerId AND status = 'DISCONNECTED'
                          AND last_checked_at > now() - make_interval(secs => :seconds)
                        """)
                .param("ownerId", ownerId)
                .param("seconds", time.toSeconds())
                .query(NukiConnectionDao::storedConnection)
                .optional();
    }

    /**
     * Stores a connection whose tokens Nuki has just given, replacing any earlier one.
     *
     * @param accessTokenExpiresAt when the access token expires
     */
    void save(UUID ownerId, byte[] accessTokenEncrypted, byte[] refreshTokenEncrypted, Instant accessTokenExpiresAt) {
        jdbc.sql("""
                        INSERT INTO nuki_connection (owner_id, access_token_encrypted, refresh_token_encrypted,
                                                     access_token_expires_at, status, last_checked_at)
                        VALUES (:ownerId, :accessTokenEncrypted, :refreshTokenEncrypted, :accessTokenExpiresAt, 'OK',
                                now())
                        ON CONFLICT (owner_id) DO UPDATE
                        SET access_token_encrypted = excluded.access_token_encrypted,
                            refresh_token_encrypted = excluded.refresh_token_encrypted,
                            access_token_expires_at = excluded.access_token_expires_at,
                            status = 'OK', last_checked_at = now()
                        """)
                .param("ownerId", ownerId)
                .param("accessTokenEncrypted", accessTokenEncrypted)
                .param("refreshTokenEncrypted", refreshTokenEncrypted)
                .param("accessTokenExpiresAt", timestamp(accessTokenExpiresAt))
                .update();
    }

    /**
     * Stores the tokens that a refresh returned, if the connection still holds the access token it had and is in
     * the state it was in: one stored in the meantime, by connecting Nuki again, stays. A disconnected connection
     * whose refresh Nuki accepted is connected again.
     *
     * @param status the connection's status when the refresh began
     * @return whether the tokens were stored
     */
    boolean saveRefreshed(UUID ownerId, ConnectionStatus status, byte[] previousAccessTokenEncrypted,
                          TokenRefresher.Refreshed refreshed) {
        return jdbc.sql("""
                        UPDATE nuki_connection
                        SET access_token_encrypted = :accessTokenEncrypted,
                            refresh_token_encrypted = :refreshTokenEncrypted,
                            access_token_expires_at = :accessTokenExpiresAt,
                            status = 'OK',
                            last_checked_at = CASE WHEN status = 'OK' THEN last_checked_at ELSE now() END
                        WHERE owner_id = :ownerId AND access_token_encrypted = :previous AND status = :status
                        """)
                .param("ownerId", ownerId)
                .param("status", status.name())
                .param("previous", previousAccessTokenEncrypted)
                .param("accessTokenEncrypted", refreshed.accessTokenEncrypted())
                .param("refreshTokenEncrypted", refreshed.refreshTokenEncrypted())
                .param("accessTokenExpiresAt", timestamp(refreshed.accessTokenExpiresAt()))
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
     * Connects a disconnected connection again if it still holds the access token that Nuki has just accepted.
     *
     * @return whether the connection went from disconnected to OK
     */
    boolean markReconnected(UUID ownerId, byte[] acceptedAccessTokenEncrypted) {
        return jdbc.sql("""
                        UPDATE nuki_connection SET status = 'OK', last_checked_at = now()
                        WHERE owner_id = :ownerId AND access_token_encrypted = :accepted AND status = 'DISCONNECTED'
                        """)
                .param("ownerId", ownerId)
                .param("accepted", acceptedAccessTokenEncrypted)
                .update() == 1;
    }

    /**
     * Stores the owner's tokens encrypted again with another key, if the connection still holds the ones that were
     * encrypted again: tokens stored in the meantime, by connecting Nuki again, stay.
     *
     * @return whether the tokens were stored
     */
    boolean saveReEncrypted(UUID ownerId, byte[] accessTokenEncrypted, byte[] refreshTokenEncrypted,
                            byte[] reEncryptedAccessToken, byte[] reEncryptedRefreshToken) {
        return jdbc.sql("""
                        UPDATE nuki_connection
                        SET access_token_encrypted = :reEncryptedAccessToken,
                            refresh_token_encrypted = :reEncryptedRefreshToken
                        WHERE owner_id = :ownerId AND access_token_encrypted = :accessTokenEncrypted
                          AND refresh_token_encrypted = :refreshTokenEncrypted
                        """)
                .param("ownerId", ownerId)
                .param("accessTokenEncrypted", accessTokenEncrypted)
                .param("refreshTokenEncrypted", refreshTokenEncrypted)
                .param("reEncryptedAccessToken", reEncryptedAccessToken)
                .param("reEncryptedRefreshToken", reEncryptedRefreshToken)
                .update() == 1;
    }

    /**
     * Locks keep their row, and their links do not change.
     *
     * @return whether the owner had one
     */
    boolean delete(UUID ownerId) {
        return jdbc.sql("DELETE FROM nuki_connection WHERE owner_id = :ownerId")
                .param("ownerId", ownerId)
                .update() == 1;
    }

    void markChecked(UUID ownerId) {
        jdbc.sql("UPDATE nuki_connection SET last_checked_at = now() WHERE owner_id = :ownerId AND status = 'OK'")
                .param("ownerId", ownerId)
                .update();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static StoredConnection storedConnection(ResultSet rs, int row) throws SQLException {
        var lastCheckedAt = rs.getObject("last_checked_at", OffsetDateTime.class);
        return new StoredConnection(rs.getBytes("access_token_encrypted"),
                rs.getBytes("refresh_token_encrypted"),
                rs.getObject("access_token_expires_at", OffsetDateTime.class).toInstant(),
                ConnectionStatus.valueOf(rs.getString("status")),
                lastCheckedAt == null ? null : lastCheckedAt.atZoneSameInstant(ZoneId.systemDefault()));
    }

    /**
     * A connection as stored.
     *
     * @param accessTokenExpiresAt when the access token expires
     */
    // Never compared, so the arrays' identity-based equals does not matter.
    @SuppressWarnings("ArrayRecordComponent")
    record StoredConnection(byte[] accessTokenEncrypted, byte[] refreshTokenEncrypted,
                            Instant accessTokenExpiresAt, ConnectionStatus status, @Nullable ZonedDateTime lastCheckedAt) {
    }
}
