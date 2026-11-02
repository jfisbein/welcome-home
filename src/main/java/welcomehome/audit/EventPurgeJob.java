package welcomehome.audit;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import welcomehome.db.OwnerTransactions;

/**
 * Deletes guests' attempts older than the retention period once a day, each owner in their own transaction,
 * so that Row-Level Security applies as everywhere else.
 */
@Component
class EventPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(EventPurgeJob.class);

    private final AccessEventDao accessEvents;
    private final OwnerTransactions ownerTransactions;
    private final Duration retention;

    EventPurgeJob(AccessEventDao accessEvents, OwnerTransactions ownerTransactions, AuditProperties properties) {
        this.accessEvents = accessEvents;
        this.ownerTransactions = ownerTransactions;
        this.retention = properties.retention();
    }

    @Scheduled(cron = "${welcomehome.audit.purge-cron}")
    void purgeAll() {
        var cutoff = Instant.now().minus(retention);
        var purged = 0;
        for (var ownerId : accessEvents.allOwnerIds()) {
            try {
                purged += ownerTransactions.execute(ownerId, () -> accessEvents.deleteOlderThan(ownerId, cutoff));
            } catch (RuntimeException e) {
                // One owner's failure must not stop the others' purge; the next run tries again.
                log.warn("Could not purge the history of owner {}: {}", ownerId, e.getMessage());
            }
        }
        if (purged > 0) {
            log.info("Purged {} access events from before {}", purged, cutoff);
        }
    }
}
