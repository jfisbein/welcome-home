package welcomehome.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * A guest's attempt was recorded in its owner's history. Published in the transaction that records it, so
 * listeners after commit only hear of recorded attempts.
 *
 * @param ownerId    the link's owner
 * @param linkId     the link the guest used
 * @param lockId     the link's lock
 * @param outcome    what the attempt came to
 * @param occurredAt when the attempt ended
 */
public record AccessAttempted(UUID ownerId, UUID linkId, UUID lockId, AccessOutcome outcome, Instant occurredAt) {
}
