package welcomehome.nuki;

import java.time.LocalDateTime;
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
                        SELECT api_token_encrypted, status, last_checked_at::timestamp AS last_checked_at
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

    record StoredConnection(byte[] apiTokenEncrypted, ConnectionStatus status, @Nullable LocalDateTime lastCheckedAt) {
    }
}
