package welcomehome.links;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import welcomehome.db.OwnerTransactions;
import welcomehome.db.Owners;

/**
 * Deletes links that ended longer ago than the retention period once a day, each owner in their own transaction, so
 * that a guest's name in a label does not stay for ever. A link goes with its schedule and its attempts, also
 * those made since it ended: its URL then answers as any unknown link does, and nothing more is recorded.
 */
@Component
class LinkPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(LinkPurgeJob.class);

    private final LinkDao links;
    private final OwnerTransactions ownerTransactions;
    private final Owners owners;
    private final Clock clock;
    private final Duration retention;

    LinkPurgeJob(LinkDao links, OwnerTransactions ownerTransactions, Owners owners, LinksProperties properties,
                 Clock clock) {
        this.clock = clock;
        this.links = links;
        this.ownerTransactions = ownerTransactions;
        this.owners = owners;
        this.retention = properties.retention();
    }

    @Scheduled(cron = "${welcomehome.links.purge-cron}")
    void purgeAll() {
        var cutoff = clock.instant().minus(retention);
        var purged = new AtomicInteger();
        owners.forEach("purge the links", ownerId -> purged.addAndGet(
                ownerTransactions.execute(ownerId, () -> links.deleteEndedBefore(ownerId, cutoff))));
        if (purged.get() > 0) {
            log.info("Purged {} links that ended before {}", purged.get(), cutoff);
        }
    }
}
