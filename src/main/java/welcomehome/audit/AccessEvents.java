package welcomehome.audit;

import java.time.Duration;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;

/**
 * Records guests' attempts to open a door, in the link owner's history, and publishes each one as an
 * {@link AccessAttempted}.
 */
@Service
public class AccessEvents {

    private final AccessEventDao accessEvents;
    private final OwnerTransactions ownerTransactions;
    private final ApplicationEventPublisher events;
    private final AuditProperties properties;

    AccessEvents(AccessEventDao accessEvents, OwnerTransactions ownerTransactions, ApplicationEventPublisher events,
                 AuditProperties properties) {
        this.accessEvents = accessEvents;
        this.ownerTransactions = ownerTransactions;
        this.events = events;
        this.properties = properties;
    }

    /**
     * How long an attempt is kept.
     *
     * @return the retention of attempts
     */
    public Duration retention() {
        return properties.retention();
    }

    /**
     * How long an attempt keeps the guest's IP address: never longer than the attempt itself.
     *
     * @return the retention of IP addresses
     */
    public Duration ipRetention() {
        var ipRetention = properties.ipRetention();
        return ipRetention.compareTo(properties.retention()) < 0 ? ipRetention : properties.retention();
    }

    /**
     * Records an attempt in its owner's history.
     *
     * @param event the attempt
     */
    public void record(AccessEvent event) {
        ownerTransactions.run(event.ownerId(), () -> {
            var occurredAt = accessEvents.insert(event);
            events.publishEvent(new AccessAttempted(event.ownerId(), event.linkId(), event.lockId(),
                    event.entranceLockId(), event.outcome(), occurredAt.toInstant()));
        });
    }
}
