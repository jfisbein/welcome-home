package welcomehome.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.SimplePropertyRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * owner_activity has Row-Level Security: call it inside {@code OwnerTransactions} for the same owner. Every
 * statement also filters by owner_id, so it never depends on RLS alone.
 */
@Repository
class OwnerActivityDao {

    private final JdbcClient jdbc;

    OwnerActivityDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID ownerId, ActivityKind kind, @Nullable UUID lockId, @Nullable UUID linkId,
                @Nullable String passkey, @Nullable String ip, @Nullable String country) {
        jdbc.sql("""
                        INSERT INTO owner_activity (owner_id, kind, lock_id, link_id, passkey, ip, country)
                        VALUES (:ownerId, :kind, :lockId, :linkId, :passkey, CAST(:ip AS inet), :country)
                        """)
                .param("ownerId", ownerId)
                .param("kind", kind.name())
                .param("lockId", lockId)
                .param("linkId", linkId)
                .param("passkey", passkey)
                .param("ip", ip)
                .param("country", country)
                .update();
    }

    /** The owner's latest entries, newest first, with the label of each link that still exists. */
    List<StoredActivity> findLatest(UUID ownerId, int limit) {
        return jdbc.sql("""
                        SELECT a.id, a.occurred_at, a.kind, a.link_id, l.label AS link_label, k.name AS lock_name,
                               a.passkey, host(a.ip) AS ip, a.country
                        FROM owner_activity a
                        LEFT JOIN access_link l ON l.owner_id = a.owner_id AND l.id = a.link_id
                        LEFT JOIN lock k ON k.owner_id = a.owner_id AND k.id = a.lock_id
                        WHERE a.owner_id = :ownerId
                        ORDER BY a.occurred_at DESC, a.id DESC
                        LIMIT :limit
                        """)
                .param("ownerId", ownerId)
                .param("limit", limit)
                .query(new SimplePropertyRowMapper<>(StoredActivity.class))
                .list();
    }

    /** @return how many entries were deleted */
    int deleteOlderThan(UUID ownerId, Instant cutoff) {
        return jdbc.sql("DELETE FROM owner_activity WHERE owner_id = :ownerId AND occurred_at < :cutoff")
                .param("ownerId", ownerId)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }

    /** @return how many entries before the cutoff forgot their IP address, keeping their country */
    int forgetIpAddresses(UUID ownerId, Instant cutoff) {
        return jdbc.sql("""
                        UPDATE owner_activity SET ip = NULL
                        WHERE owner_id = :ownerId AND occurred_at < :cutoff AND ip IS NOT NULL
                        """)
                .param("ownerId", ownerId)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }
}
