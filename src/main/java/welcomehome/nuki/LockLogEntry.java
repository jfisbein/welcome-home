package welcomehome.nuki;

import java.time.Instant;

/**
 * An entry in a lock's log.
 *
 * @param occurredAt when it happened
 * @param action     what happened
 * @param trigger    where it came from
 * @param result     how it ended
 * @param nukiCodes  the entry's action, trigger and result as Nuki's numbers, such as {@code 3/0/0}: for the app's
 *                   log, as the types above leave out the codes they do not know
 */
public record LockLogEntry(Instant occurredAt, LoggedAction action, LockTrigger trigger, ActionResult result,
                           String nukiCodes) {

    /**
     * Tells whether the action completed.
     *
     * @return whether the result is {@link ActionResult#SUCCEEDED}
     */
    public boolean succeeded() {
        return result == ActionResult.SUCCEEDED;
    }
}
