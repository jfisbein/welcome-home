package welcomehome.links;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import welcomehome.db.OwnerTransactions;

/**
 * Deletes links that ended longer ago than the retention period once a day, each owner in their own transaction, so
 * that a guest's name in a label does not stay for ever. A link goes with its schedule and its attempts, once none
 * of those is younger than the retention period either.
 */
@Component
class LinkPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(LinkPurgeJob.class);

    private final LinkDao links;
    private final OwnerTransactions ownerTransactions;
    private final Duration retention;

    LinkPurgeJob(LinkDao links, OwnerTransactions ownerTransactions, LinksProperties properties) {
        this.links = links;
        this.ownerTransactions = ownerTransactions;
        this.retention = properties.retention();
    }

    @Scheduled(cron = "${welcomehome.links.purge-cron}")
    void purgeAll() {
        var cutoff = Instant.now().minus(retention);
        var purged = 0;
        for (var ownerId : links.allOwnerIds()) {
            try {
                purged += ownerTransactions.execute(ownerId, () -> links.deleteEndedBefore(ownerId, cutoff));
            } catch (RuntimeException e) {
                // One owner's failure must not stop the others' purge; the next run tries again.
                log.warn("Could not purge the links of owner {}: {}", ownerId, e.getMessage());
            }
        }
        if (purged > 0) {
            log.info("Purged {} links that ended before {}", purged, cutoff);
        }
    }
}
