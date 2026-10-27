package welcomehome.access;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import welcomehome.audit.AccessOutcome;
import welcomehome.nuki.LockAction;
import welcomehome.nuki.LockActionState;
import welcomehome.nuki.LockLogEntry;
import welcomehome.nuki.LockProvider;
import welcomehome.nuki.LockProviderException;
import welcomehome.nuki.LockTrigger;
import welcomehome.nuki.PendingAction;

/**
 * Waits for a lock to confirm an unlatch that Nuki accepted: Nuki's report of its outcome, where it sends one,
 * or the lock's log, read until an unlatch entry from after the request shows up, whichever comes first. The log
 * stays as the way that always works: Nuki reports only to owners connected with OAuth2, and only once its
 * webhook URL is set; it documents no retries, and turns the webhooks off after too many failures. An unlatch by
 * hand, with the lock's button or by an accessory in the same few seconds is someone else's, not the guest's, so
 * it does not count. An Opener's electric strike actuation is taken to show up the same way, as Nuki's action 3;
 * Nuki does not document its log, so an opening that the log does not confirm is logged with the entries the log
 * had.
 */
@Component
class UnlatchConfirmation {

    private static final Logger log = LoggerFactory.getLogger(UnlatchConfirmation.class);

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
     * @param unlatch     the unlatch Nuki accepted, which it may report
     * @return {@link AccessOutcome#OPENED}, {@link AccessOutcome#LOCK_FAILED}, {@link AccessOutcome#NIGHT_MODE}, or
     * {@link AccessOutcome#NOT_CONFIRMED} if neither a report nor the log told in time
     */
    AccessOutcome await(UUID ownerId, long nukiSmartlockId, Instant requestedAt, PendingAction unlatch) {
        var deadline = Instant.now().plus(settings.timeout());
        List<LockLogEntry> since = List.of();
        while (true) {
            // Between two reads of the log, a report ends the wait at once.
            var reported = unlatch.awaitOutcome(settings.interval());
            if (reported.isPresent()) {
                return result(reported.get());
            }
            try {
                since = lockProvider.getRecentLog(ownerId, nukiSmartlockId).stream()
                        .filter(entry -> !entry.occurredAt().isBefore(requestedAt.minus(CLOCK_SKEW)))
                        .toList();
                var logged = since.stream()
                        .filter(entry -> entry.action() == LockAction.UNLATCH && NUKI_WEB_API.contains(entry.trigger()))
                        .findFirst();
                if (logged.isPresent()) {
                    return result(logged.get().state());
                }
            } catch (LockProviderException e) {
                // Nuki failing may pass, so keep trying until the deadline; without the permission to read the
                // log, or without a connection, waiting changes nothing.
                if (e.reason() != LockProviderException.Reason.NUKI_ERROR) {
                    return AccessOutcome.NOT_CONFIRMED;
                }
            }
            if (!Instant.now().isBefore(deadline)) {
                log.info("Nuki smartlock {} logged no unlatch within {}; its entries since the request: {}",
                        nukiSmartlockId, settings.timeout(), since);
                return AccessOutcome.NOT_CONFIRMED;
            }
        }
    }

    private static AccessOutcome result(LockActionState state) {
        return switch (state) {
            case SUCCEEDED -> AccessOutcome.OPENED;
            case REJECTED_NIGHT_MODE -> AccessOutcome.NIGHT_MODE;
            case MOTOR_BLOCKED, FAILED -> AccessOutcome.LOCK_FAILED;
        };
    }
}
