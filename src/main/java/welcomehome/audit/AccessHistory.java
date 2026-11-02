package welcomehome.audit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;

/**
 * An owner's history of guests' attempts, per lock and per link, newest first. Pages are cut by date and id:
 * the next page holds the events before the last one shown, in that order, so that attempts at the same instant
 * are neither lost nor repeated across pages.
 */
@Service
class AccessHistory {

    private final AccessEventDao accessEvents;
    private final OwnerTransactions ownerTransactions;

    AccessHistory(AccessEventDao accessEvents, OwnerTransactions ownerTransactions) {
        this.accessEvents = accessEvents;
        this.ownerTransactions = ownerTransactions;
    }

    /**
     * Finds a lock's events: those of its links, at the lock or at its building entrance, and for an Opener also
     * those of the locks whose entrance it is.
     *
     * @param linkId only this link's events, or all of the lock's if null
     * @param before only events before this one, or the latest if null
     */
    HistoryPage find(UUID ownerId, UUID lockId, @Nullable UUID linkId, @Nullable Cursor before, int pageSize) {
        var events = ownerTransactions.execute(ownerId,
                () -> accessEvents.findByLock(ownerId, lockId, linkId, before, pageSize + 1));
        if (events.size() <= pageSize) {
            return new HistoryPage(events, null);
        }
        var shown = events.subList(0, pageSize);
        var last = shown.getLast();
        return new HistoryPage(shown, new Cursor(last.occurredAt().toInstant(), last.id()));
    }

    /** @return the label of the lock's link, or empty if the lock has no such link */
    Optional<String> linkLabel(UUID ownerId, UUID lockId, UUID linkId) {
        return ownerTransactions.execute(ownerId, () -> accessEvents.findLinkLabel(ownerId, lockId, linkId));
    }

    /**
     * @param events      the events of the page, newest first
     * @param olderBefore where the next page starts, or null if there are no older events
     */
    record HistoryPage(List<StoredAttempt> events, @Nullable Cursor olderBefore) {
    }

    /**
     * An event's place in the history: events before it are older, or as old with a smaller id. Without an id,
     * as in a URL from an earlier version, only older events are before it.
     *
     * @param occurredAt when the event happened
     * @param id         the event's id, or null
     */
    record Cursor(Instant occurredAt, @Nullable UUID id) {
    }
}
