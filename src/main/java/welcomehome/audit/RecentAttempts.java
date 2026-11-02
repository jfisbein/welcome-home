package welcomehome.audit;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;

/**
 * An owner's latest guest attempts across all their locks, for the home page.
 */
@Service
public class RecentAttempts {

    private final AccessEventDao accessEvents;
    private final OwnerTransactions ownerTransactions;
    private final AttemptRows rows;

    RecentAttempts(AccessEventDao accessEvents, OwnerTransactions ownerTransactions, AttemptRows rows) {
        this.accessEvents = accessEvents;
        this.ownerTransactions = ownerTransactions;
        this.rows = rows;
    }

    /**
     * Finds the owner's latest attempts, newest first.
     *
     * @param ownerId the owner
     * @param limit   how many attempts at most
     * @param locale  the language of the outcomes and countries
     * @return the attempts, each in its lock's time zone
     */
    public List<AttemptRow> latest(UUID ownerId, int limit, Locale locale) {
        return ownerTransactions.execute(ownerId, () -> accessEvents.findLatest(ownerId, limit)).stream()
                .map(event -> rows.row(event.occurredAt(), ZoneId.of(event.timeZone()), event.lockId(),
                        event.lockName(), event.linkId(), event.linkLabel(), event.entranceName(), event.result(),
                        event.reason(), event.ip(), event.country(), locale))
                .toList();
    }
}
