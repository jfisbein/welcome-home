package welcomehome.audit;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;

/**
 * Records guests' attempts to open a door, in the link owner's history, and publishes each one as an
 * {@link AccessAttempted}.
 */
@Service
public class AccessEvents {

    /** Longer user agents are cut: they are for the owner to recognise a device, not to fingerprint it. */
    private static final int MAX_USER_AGENT_LENGTH = 500;

    private final AccessEventDao accessEvents;
    private final OwnerTransactions ownerTransactions;
    private final ApplicationEventPublisher events;

    AccessEvents(AccessEventDao accessEvents, OwnerTransactions ownerTransactions, ApplicationEventPublisher events) {
        this.accessEvents = accessEvents;
        this.ownerTransactions = ownerTransactions;
        this.events = events;
    }

    /**
     * Records an attempt in its owner's history.
     *
     * @param event the attempt
     */
    public void record(AccessEvent event) {
        var userAgent = event.userAgent();
        if (userAgent != null && userAgent.length() > MAX_USER_AGENT_LENGTH) {
            userAgent = userAgent.substring(0, MAX_USER_AGENT_LENGTH);
        }
        var trimmedUserAgent = userAgent;
        ownerTransactions.run(event.ownerId(), () -> {
            var occurredAt = accessEvents.insert(event, trimmedUserAgent);
            events.publishEvent(new AccessAttempted(event.ownerId(), event.linkId(), event.lockId(),
                    event.entranceLockId(), event.outcome(), occurredAt.toInstant()));
        });
    }
}
