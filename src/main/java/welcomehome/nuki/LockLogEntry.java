package welcomehome.nuki;

import java.time.Instant;

/**
 * An entry in a lock's log.
 *
 * @param occurredAt when it happened
 * @param action     what happened
 * @param state      how it ended
 */
public record LockLogEntry(Instant occurredAt, LockAction action, LockActionState state) {

    /**
     * Tells whether the action completed.
     *
     * @return whether the state is {@link LockActionState#SUCCEEDED}
     */
    public boolean succeeded() {
        return state == LockActionState.SUCCEEDED;
    }
}
