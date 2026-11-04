package welcomehome.audit;

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
import welcomehome.audit.AccessHistory.Cursor;

/**
 * access_event has Row-Level Security: call it inside {@code OwnerTransactions} for the same owner. Every
 * statement also filters by owner_id, so it never depends on RLS alone.
 */
@Repository
class AccessEventDao {

    /** What every page's attempts are read from: each with the names of its link, its lock and its entrance. */
    private static final String ATTEMPTS = """
            SELECT e.id, e.occurred_at, k.time_zone, e.lock_id, k.name AS lock_name, e.link_id, l.label AS link_label,
                   d.name AS entrance_name, e.result, e.reason, host(e.ip) AS ip, e.country, e.distance_m
            FROM access_event e
            JOIN access_link l ON l.owner_id = e.owner_id AND l.id = e.link_id
            JOIN lock k ON k.owner_id = e.owner_id AND k.id = e.lock_id
            LEFT JOIN lock d ON d.owner_id = e.owner_id AND d.id = e.entrance_lock_id
            """;

    private final JdbcClient jdbc;

    AccessEventDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records an attempt without the guest's browser, which nothing shows: {@code user_agent} stays for the release
     * before, which still writes it.
     *
     * @return when the attempt was recorded, by the database's clock
     */
    OffsetDateTime insert(AccessEvent event) {
        return jdbc.sql("""
                        INSERT INTO access_event (owner_id, link_id, lock_id, entrance_lock_id, result, reason,
                                                  ip, country, distance_m, accuracy_m, location_confirmed)
                        VALUES (:ownerId, :linkId, :lockId, :entranceLockId, :result, :reason, CAST(:ip AS inet),
                                :country, :distanceM, :accuracyM, :locationConfirmed)
                        RETURNING occurred_at
                        """)
                .param("ownerId", event.ownerId())
                .param("linkId", event.linkId())
                .param("lockId", event.lockId())
                .param("entranceLockId", event.entranceLockId())
                .param("result", event.outcome().result().name())
                .param("reason", event.outcome().code())
                .param("ip", event.ip())
                .param("country", event.country())
                .param("distanceM", event.distanceM())
                .param("accuracyM", event.accuracyM())
                .param("locationConfirmed", event.locationConfirmed())
                .query(OffsetDateTime.class)
                .single();
    }

    /**
     * Finds a lock's events, newest first.
     *
     * @param linkId only this link's events, or all of the lock's if null
     * @param before only events before this one, or the latest if null
     * @param limit  how many events at most
     */
    List<StoredAttempt> findByLock(UUID ownerId, UUID lockId, @Nullable UUID linkId, @Nullable Cursor before,
                                   int limit) {
        return jdbc.sql(ATTEMPTS + """
                        WHERE e.owner_id = :ownerId AND (e.lock_id = :lockId OR e.entrance_lock_id = :lockId)
                          AND (CAST(:linkId AS uuid) IS NULL OR e.link_id = :linkId)
                          AND (CAST(:before AS timestamptz) IS NULL OR e.occurred_at < :before
                               OR (e.occurred_at = :before AND e.id < CAST(:beforeId AS uuid)))
                        ORDER BY e.occurred_at DESC, e.id DESC
                        LIMIT :limit
                        """)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .param("linkId", linkId)
                .param("before", before == null ? null : OffsetDateTime.ofInstant(before.occurredAt(), ZoneOffset.UTC))
                .param("beforeId", before == null ? null : before.id())
                .param("limit", limit)
                .query(new SimplePropertyRowMapper<>(StoredAttempt.class))
                .list();
    }

    /** @return the label of the lock's link, or empty if the lock has no such link */
    Optional<String> findLinkLabel(UUID ownerId, UUID lockId, UUID linkId) {
        return jdbc.sql("""
                        SELECT label FROM access_link WHERE owner_id = :ownerId AND lock_id = :lockId AND id = :linkId
                        """)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .param("linkId", linkId)
                .query(String.class)
                .optional();
    }

    /** The owner's latest events across all their locks, newest first. */
    List<StoredAttempt> findLatest(UUID ownerId, int limit) {
        return jdbc.sql(ATTEMPTS + """
                        WHERE e.owner_id = :ownerId
                        ORDER BY e.occurred_at DESC, e.id DESC
                        LIMIT :limit
                        """)
                .param("ownerId", ownerId)
                .param("limit", limit)
                .query(new SimplePropertyRowMapper<>(StoredAttempt.class))
                .list();
    }

    /** @return how many events were deleted */
    int deleteOlderThan(UUID ownerId, Instant cutoff) {
        return jdbc.sql("DELETE FROM access_event WHERE owner_id = :ownerId AND occurred_at < :cutoff")
                .param("ownerId", ownerId)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }

    /**
     * Forgets the IP address of attempts before the cutoff, which keep their country, with their distance to the
     * door, which says where the guest was too, and the browser of every attempt, which an earlier release stored.
     *
     * @return how many attempts were changed
     */
    int forgetIpAddresses(UUID ownerId, Instant cutoff) {
        return jdbc.sql("""
                        UPDATE access_event
                        SET ip = CASE WHEN occurred_at < :cutoff THEN NULL ELSE ip END,
                            distance_m = CASE WHEN occurred_at < :cutoff THEN NULL ELSE distance_m END,
                            accuracy_m = CASE WHEN occurred_at < :cutoff THEN NULL ELSE accuracy_m END,
                            user_agent = NULL
                        WHERE owner_id = :ownerId
                          AND ((occurred_at < :cutoff AND (ip IS NOT NULL OR distance_m IS NOT NULL
                                                           OR accuracy_m IS NOT NULL))
                               OR user_agent IS NOT NULL)
                        """)
                .param("ownerId", ownerId)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }

    /** From owner, which has no Row-Level Security: without an owner context access_event shows no rows. */
    List<UUID> allOwnerIds() {
        return jdbc.sql("SELECT id FROM owner")
                .query((rs, row) -> rs.getObject("id", UUID.class))
                .list();
    }
}
