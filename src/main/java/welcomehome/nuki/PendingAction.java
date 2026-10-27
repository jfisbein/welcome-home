package welcomehome.nuki;

import java.time.Duration;
import java.util.Optional;

/**
 * An action that a lock provider accepted and runs asynchronously, whose outcome the provider may report once the
 * lock has run it, as Nuki does with a webhook. When it is not reported, only the lock's log tells.
 */
public interface PendingAction {

    /** An action whose outcome is never reported: waiting for it only waits. */
    PendingAction UNREPORTED = timeout -> {
        try {
            Thread.sleep(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    };

    /**
     * Waits for the action's outcome to be reported.
     *
     * @param timeout how long to wait at most
     * @return how the action ended, as soon as it is reported, or empty if it is not within the timeout
     */
    Optional<LockActionState> awaitOutcome(Duration timeout);
}
