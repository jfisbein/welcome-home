package welcomehome.nuki;

import java.time.Instant;

/**
 * An entry in a lock's log.
 *
 * @param occurredAt when it happened
 * @param action     what happened
 * @param trigger    where it came from
 * @param state      how it ended
 * @param nukiCodes  the entry's action, trigger and state as Nuki's numbers, such as {@code 3/0/0}: for the app's
 *                   log, as the types above leave out the codes they do not know
 */
public record LockLogEntry(Instant occurredAt, LockAction action, LockTrigger trigger, LockActionState state,
                           String nukiCodes) {

    /**
     * Tells whether the action completed.
     *
     * @return whether the state is {@link LockActionState#SUCCEEDED}
     */
    public boolean succeeded() {
        return state == LockActionState.SUCCEEDED;
    }
}
