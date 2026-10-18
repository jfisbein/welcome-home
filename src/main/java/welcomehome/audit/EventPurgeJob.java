package welcomehome.audit;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    private final JdbcClient jdbc;
    private final OwnerTransactions ownerTransactions;
    private final Duration retention;

    EventPurgeJob(JdbcClient jdbc, OwnerTransactions ownerTransactions, AuditProperties properties) {
        this.jdbc = jdbc;
        this.ownerTransactions = ownerTransactions;
        this.retention = properties.retention();
    }

    @Scheduled(cron = "${welcomehome.audit.purge-cron}")
    void purgeAll() {
        var cutoff = Instant.now().minus(retention);
        var purged = 0;
        var ownerIds = jdbc.sql("SELECT id FROM owner").query((rs, row) -> rs.getObject("id", UUID.class)).list();
        for (var ownerId : ownerIds) {
            try {
                purged += ownerTransactions.execute(ownerId, () -> jdbc.sql("""
                                DELETE FROM access_event WHERE owner_id = :ownerId AND occurred_at < :cutoff
                                """)
                        .param("ownerId", ownerId)
                        .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                        .update());
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
