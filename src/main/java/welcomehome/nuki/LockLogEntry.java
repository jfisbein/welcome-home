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
     * Tells whether this is an unlatch asked of the lock remotely, as a guest's is, rather than one by hand, with
     * its button or by an accessory. Nuki documents the trigger 4 (web), but the unlatches welcome-home sent were
     * logged with 0 (system), as were the owner's own from the Nuki app; so an unlatch from the app at the same
     * moment is still taken for the guest's.
     *
     * @return whether it may be the unlatch that a guest's opening asked for
     */
    public boolean isRemoteUnlatch() {
        return action == LoggedAction.UNLATCH && (trigger == LockTrigger.SYSTEM || trigger == LockTrigger.WEB);
    }

    /**
     * Tells whether the action completed.
     *
     * @return whether the result is {@link ActionResult#SUCCEEDED}
     */
    public boolean succeeded() {
        return result == ActionResult.SUCCEEDED;
    }
}
