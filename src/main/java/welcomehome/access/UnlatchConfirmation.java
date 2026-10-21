package welcomehome.access;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import welcomehome.audit.AccessOutcome;
import welcomehome.nuki.LockAction;
import welcomehome.nuki.LockActionState;
import welcomehome.nuki.LockLogEntry;
import welcomehome.nuki.LockProvider;
import welcomehome.nuki.LockProviderException;
import welcomehome.nuki.LockTrigger;

/**
 * Waits for a lock to confirm an unlatch that Nuki accepted, by reading the lock's log until an unlatch
 * entry from after the request shows up. An unlatch by hand, with the lock's button or by an accessory in
 * the same few seconds is someone else's, not the guest's, so it does not count.
 */
@Component
class UnlatchConfirmation {

    /**
     * The triggers an unlatch through the Nuki Web API may carry. Nuki documents 4 (web), but the unlatches
     * welcome-home sent were logged with 0 (system), as were the owner's own from the Nuki app; so an unlatch
     * from the app at the same moment is still taken for the guest's.
     */
    static final Set<LockTrigger> NUKI_WEB_API = EnumSet.of(LockTrigger.SYSTEM, LockTrigger.WEB);

    /** The lock's clock and the server's may differ a little: entries this much older than the request count. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    private final LockProvider lockProvider;
    private final AccessProperties.Confirmation settings;

    UnlatchConfirmation(LockProvider lockProvider, AccessProperties properties) {
        this.lockProvider = lockProvider;
        this.settings = properties.confirmation();
    }

    /**
     * @param requestedAt when the unlatch was sent to Nuki
     * @return {@link AccessOutcome#OPENED}, {@link AccessOutcome#LOCK_FAILED}, {@link AccessOutcome#NIGHT_MODE}, or
     * {@link AccessOutcome#NOT_CONFIRMED} if the log did not show the unlatch in time
     */
    AccessOutcome await(UUID ownerId, long nukiSmartlockId, Instant requestedAt) {
        var deadline = Instant.now().plus(settings.timeout());
        while (true) {
            sleep(settings.interval());
            try {
                var unlatch = lockProvider.getRecentLog(ownerId, nukiSmartlockId).stream()
                        .filter(entry -> entry.action() == LockAction.UNLATCH && NUKI_WEB_API.contains(entry.trigger()))
                        .filter(entry -> !entry.occurredAt().isBefore(requestedAt.minus(CLOCK_SKEW)))
                        .findFirst();
                if (unlatch.isPresent()) {
                    return result(unlatch.get());
                }
            } catch (LockProviderException e) {
                // Nuki failing may pass, so keep trying until the deadline; without the permission to read the
                // log, or without a connection, waiting changes nothing.
                if (e.reason() != LockProviderException.Reason.NUKI_ERROR) {
                    return AccessOutcome.NOT_CONFIRMED;
                }
            }
            if (!Instant.now().isBefore(deadline)) {
                return AccessOutcome.NOT_CONFIRMED;
            }
        }
    }

    private static AccessOutcome result(LockLogEntry unlatch) {
        if (unlatch.state() == LockActionState.REJECTED_NIGHT_MODE) {
            return AccessOutcome.NIGHT_MODE;
        }
        return unlatch.succeeded() ? AccessOutcome.OPENED : AccessOutcome.LOCK_FAILED;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
