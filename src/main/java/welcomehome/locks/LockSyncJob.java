package welcomehome.locks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import welcomehome.nuki.LockProviderException;
import welcomehome.nuki.LockProviderException.Reason;

/**
 * Brings every owner's locks in line with their Nuki account once a day, so that a lock removed from it shows as
 * such on the owner's pages without their pressing anything.
 */
@Component
class LockSyncJob {

    private static final Logger log = LoggerFactory.getLogger(LockSyncJob.class);

    private final LockSync lockSync;
    private final LockDao locks;

    LockSyncJob(LockSync lockSync, LockDao locks) {
        this.lockSync = lockSync;
        this.locks = locks;
    }

    @Scheduled(cron = "${welcomehome.locks.sync-cron}")
    void syncAll() {
        for (var ownerId : locks.allOwnerIds()) {
            try {
                var result = lockSync.syncDaily(ownerId);
                if (result.added() > 0 || result.deactivated() > 0) {
                    log.info("Synced the locks of owner {}: {} new, {} no longer in Nuki", ownerId, result.added(),
                            result.deactivated());
                }
            } catch (LockProviderException e) {
                if (e.reason() != Reason.NOT_CONNECTED) {
                    log.warn("Could not sync the locks of owner {}: {}", ownerId, e.getMessage());
                }
            } catch (RuntimeException e) {
                // One owner's failure must not stop the others' sync; the next run tries again.
                log.warn("Could not sync the locks of owner {}: {}", ownerId, e.getMessage());
            }
        }
    }
}
