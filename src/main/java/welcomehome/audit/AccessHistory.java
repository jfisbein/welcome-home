package welcomehome.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;

/**
 * An owner's history of guests' attempts, per lock and per link, newest first. Pages are cut by date: the
 * next page holds the events before the last one shown.
 */
@Service
class AccessHistory {

    private final JdbcClient jdbc;
    private final OwnerTransactions ownerTransactions;

    AccessHistory(JdbcClient jdbc, OwnerTransactions ownerTransactions) {
        this.jdbc = jdbc;
        this.ownerTransactions = ownerTransactions;
    }

    /**
     * @param linkId only this link's events, or all of the lock's if null
     * @param before only events before this instant, or the latest if null
     */
    HistoryPage find(UUID ownerId, UUID lockId, @Nullable UUID linkId, @Nullable Instant before, int pageSize) {
        var events = ownerTransactions.execute(ownerId, () -> jdbc.sql("""
                        SELECT e.occurred_at, e.link_id, l.label AS link_label, e.result, e.reason, host(e.ip) AS ip
                        FROM access_event e
                        JOIN access_link l ON l.owner_id = e.owner_id AND l.id = e.link_id
                        WHERE e.owner_id = :ownerId AND e.lock_id = :lockId
                          AND (CAST(:linkId AS uuid) IS NULL OR e.link_id = :linkId)
                          AND (CAST(:before AS timestamptz) IS NULL OR e.occurred_at < :before)
                        ORDER BY e.occurred_at DESC
                        LIMIT :limit
                        """)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .param("linkId", linkId)
                .param("before", before == null ? null : OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
                .param("limit", pageSize + 1)
                .query(HistoryEvent.class)
                .list());
        if (events.size() <= pageSize) {
            return new HistoryPage(events, null);
        }
        var shown = events.subList(0, pageSize);
        return new HistoryPage(shown, shown.getLast().occurredAt().toInstant());
    }

    /** @return the label of the lock's link, or empty if the lock has no such link */
    Optional<String> linkLabel(UUID ownerId, UUID lockId, UUID linkId) {
        return ownerTransactions.execute(ownerId, () -> jdbc.sql("""
                        SELECT label FROM access_link WHERE owner_id = :ownerId AND lock_id = :lockId AND id = :linkId
                        """)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .param("linkId", linkId)
                .query(String.class)
                .optional());
    }

    /**
     * A guest's attempt.
     *
     * @param reason the short code that {@link AccessEvent} recorded
     * @param ip     the guest's IP address
     */
    record HistoryEvent(OffsetDateTime occurredAt, UUID linkId, String linkLabel, AccessResult result,
                        @Nullable String reason, @Nullable String ip) {
    }

    /**
     * @param events      the events of the page, newest first
     * @param olderBefore where the next page starts, or null if there are no older events
     */
    record HistoryPage(List<HistoryEvent> events, @Nullable Instant olderBefore) {
    }
}
