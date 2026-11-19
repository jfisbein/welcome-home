package welcomehome.nuki;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import welcomehome.db.Owners;

/**
 * Every hour, tries the connections disconnected lately, so that one that Nuki rejected by mistake does not wait for
 * its owner. That a connection still works is checked by the daily sync of every owner's locks, which reads them
 * from Nuki with it ({@code locks.LockSyncJob}).
 */
@Component
class ReconnectionJob {

    private static final Logger log = LoggerFactory.getLogger(ReconnectionJob.class);

    private final NukiConnections connections;
    private final Owners owners;

    ReconnectionJob(NukiConnections connections, Owners owners) {
        this.connections = connections;
        this.owners = owners;
    }

    @Scheduled(cron = "${welcomehome.nuki.recheck-cron}")
    void recheckDisconnected() {
        for (var ownerId : owners.ids()) {
            try {
                if (connections.recheck(ownerId)) {
                    log.info("Nuki accepts the connection of owner {} again: connected", ownerId);
                }
            } catch (RuntimeException e) {
                log.warn("Could not try the Nuki connection of owner {} again: {}", ownerId, e.getMessage());
            }
        }
    }
}
