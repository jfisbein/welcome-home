package welcomehome.nuki;

import java.time.LocalDateTime;
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
                        SELECT id, api_token_encrypted, status, last_checked_at::timestamp AS last_checked_at
                        FROM nuki_connection
                        WHERE owner_id = :ownerId
                        """)
                .param("ownerId", ownerId)
                .query(StoredConnection.class)
                .optional();
    }

    /** Stores a token that Nuki has just accepted, replacing any earlier one. */
    void save(UUID ownerId, byte[] apiTokenEncrypted) {
        jdbc.sql("""
                        INSERT INTO nuki_connection (owner_id, api_token_encrypted, status, last_checked_at)
                        VALUES (:ownerId, :apiTokenEncrypted, 'OK', now())
                        ON CONFLICT (owner_id) DO UPDATE
                        SET api_token_encrypted = excluded.api_token_encrypted, status = 'OK', last_checked_at = now()
                        """)
                .param("ownerId", ownerId)
                .param("apiTokenEncrypted", apiTokenEncrypted)
                .update();
    }

    /**
     * Disconnects the owner's connection if it still holds the given token: a token pasted while the
     * rejected one was in use stays connected.
     */
    void markDisconnected(UUID ownerId, byte[] rejectedApiTokenEncrypted) {
        jdbc.sql("""
                        UPDATE nuki_connection SET status = 'DISCONNECTED', last_checked_at = now()
                        WHERE owner_id = :ownerId AND api_token_encrypted = :rejected
                        """)
                .param("ownerId", ownerId)
                .param("rejected", rejectedApiTokenEncrypted)
                .update();
    }

    /** Locks keep their row: their connection_id becomes NULL, and their links do not change. */
    void delete(UUID ownerId) {
        jdbc.sql("DELETE FROM nuki_connection WHERE owner_id = :ownerId")
                .param("ownerId", ownerId)
                .update();
    }

    void markChecked(UUID ownerId) {
        jdbc.sql("UPDATE nuki_connection SET last_checked_at = now() WHERE owner_id = :ownerId AND status = 'OK'")
                .param("ownerId", ownerId)
                .update();
    }

    /** From owner, which has no Row-Level Security: without an owner context nuki_connection shows no rows. */
    List<UUID> allOwnerIds() {
        return jdbc.sql("SELECT id FROM owner ORDER BY created_at").query(UUID.class).list();
    }

    record StoredConnection(UUID id, byte[] apiTokenEncrypted, ConnectionStatus status, @Nullable LocalDateTime lastCheckedAt) {
    }
}
