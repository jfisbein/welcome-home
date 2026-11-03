package welcomehome.locks;

import java.util.List;
import java.util.UUID;

/**
 * Problems started at one of an owner's locks since it was last checked. Published in the transaction that records
 * them, once per problem until it ends.
 *
 * @param ownerId  the owner
 * @param lockId   the lock
 * @param lockName its name
 * @param problems the problems that started, among {@link LockWarning#OFFLINE}, {@link LockWarning#BATTERY_CRITICAL}
 *                 and {@link LockWarning#MOTOR_BLOCKED}
 */
public record LockProblemsFound(UUID ownerId, UUID lockId, String lockName, List<LockWarning> problems) {
}
