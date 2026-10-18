package welcomehome.audit;

import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    private final JdbcClient jdbc;
    private final OwnerTransactions ownerTransactions;
    private final ApplicationEventPublisher events;

    AccessEvents(JdbcClient jdbc, OwnerTransactions ownerTransactions, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
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
            var occurredAt = jdbc.sql("""
                            INSERT INTO access_event (owner_id, link_id, lock_id, result, reason, ip, user_agent)
                            VALUES (:ownerId, :linkId, :lockId, :result, :reason, CAST(:ip AS inet), :userAgent)
                            RETURNING occurred_at
                            """)
                    .param("ownerId", event.ownerId())
                    .param("linkId", event.linkId())
                    .param("lockId", event.lockId())
                    .param("result", event.result().name())
                    .param("reason", event.reason())
                    .param("ip", event.ip())
                    .param("userAgent", trimmedUserAgent)
                    .query(OffsetDateTime.class)
                    .single();
            events.publishEvent(new AccessAttempted(event.ownerId(), event.linkId(), event.lockId(), event.result(),
                    event.reason(), occurredAt.toInstant()));
        });
    }
}
