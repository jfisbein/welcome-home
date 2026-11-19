package welcomehome.audit;

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
 * Once a day, deletes guests' attempts and owners' activity older than the retention period, and forgets the IP
 * address of those older than its own, shorter one, each owner in their own transaction, so that Row-Level
 * Security applies as everywhere else.
 */
@Component
class EventPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(EventPurgeJob.class);

    private final AccessEventDao accessEvents;
    private final OwnerActivityDao activity;
    private final OwnerTransactions ownerTransactions;
    private final Owners owners;
    private final Duration retention;
    private final Duration ipRetention;

    EventPurgeJob(AccessEventDao accessEvents, OwnerActivityDao activity, OwnerTransactions ownerTransactions,
                  Owners owners, AuditProperties properties) {
        this.owners = owners;
        this.accessEvents = accessEvents;
        this.activity = activity;
        this.ownerTransactions = ownerTransactions;
        this.retention = properties.retention();
        this.ipRetention = properties.ipRetention();
    }

    @Scheduled(cron = "${welcomehome.audit.purge-cron}")
    void purgeAll() {
        var now = Instant.now();
        var cutoff = now.minus(retention);
        var ipCutoff = now.minus(ipRetention);
        var purged = new AtomicInteger();
        var forgotten = new AtomicInteger();
        owners.forEach("purge the history", ownerId -> {
            purged.addAndGet(ownerTransactions.execute(ownerId, () -> accessEvents.deleteOlderThan(ownerId, cutoff)));
            forgotten.addAndGet(ownerTransactions.execute(ownerId,
                    () -> accessEvents.forgetIpAddresses(ownerId, ipCutoff)));
            purged.addAndGet(ownerTransactions.execute(ownerId, () -> activity.deleteOlderThan(ownerId, cutoff)));
            forgotten.addAndGet(ownerTransactions.execute(ownerId, () -> activity.forgetIpAddresses(ownerId, ipCutoff)));
        });
        if (purged.get() > 0) {
            log.info("Purged {} access events and activity entries from before {}", purged.get(), cutoff);
        }
        if (forgotten.get() > 0) {
            log.info("Forgot the IP address or browser of {} access events and activity entries", forgotten.get());
        }
    }
}
