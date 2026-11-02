package welcomehome.nuki;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks every owner's Nuki connection once a day, each owner in their own transactions, so that a revoked
 * API token shows up on the owner's panel before a guest finds the door closed. Every hour it also tries the
 * connections disconnected lately, so that one that Nuki rejected by mistake does not wait for its owner.
 */
@Component
class ConnectionCheckJob {

    private static final Logger log = LoggerFactory.getLogger(ConnectionCheckJob.class);

    private final NukiConnections connections;

    ConnectionCheckJob(NukiConnections connections) {
        this.connections = connections;
    }

    @Scheduled(cron = "${welcomehome.nuki.check-cron}")
    void checkAll() {
        for (var ownerId : connections.ownersToCheck()) {
            try {
                connections.check(ownerId)
                        .filter(status -> status == ConnectionStatus.DISCONNECTED)
                        .ifPresent(_ -> log.info("Nuki rejected the API token of owner {}: disconnected", ownerId));
            } catch (RuntimeException e) {
                // One owner's failure must not stop the others' checks; the next run tries again.
                log.warn("Could not check the Nuki connection of owner {}: {}", ownerId, e.getMessage());
            }
        }
    }

    @Scheduled(cron = "${welcomehome.nuki.recheck-cron}")
    void recheckDisconnected() {
        for (var ownerId : connections.ownersToCheck()) {
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
