package welcomehome.nuki;

import java.time.Instant;

/**
 * An entry in a lock's log.
 *
 * @param occurredAt when it happened
 * @param action     what happened
 * @param succeeded  whether the action completed: false when the motor was blocked, it was canceled, and so on
 */
public record LockLogEntry(Instant occurredAt, LockAction action, boolean succeeded) {
}
