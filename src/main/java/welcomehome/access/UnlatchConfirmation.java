package welcomehome.access;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import welcomehome.audit.AccessOutcome;
import welcomehome.nuki.ActionResult;
import welcomehome.nuki.LockLogEntry;
import welcomehome.nuki.LockProvider;
import welcomehome.nuki.LockProviderException;
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

    /** The lock's clock and the server's may differ a little: entries this much older than the request count. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    /** Reads of the log run apart, so that the wait for one can end before it does. */
    private static final Executor READS = Executors.newVirtualThreadPerTaskExecutor();

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
        // How long to wait, not what time it is: the real clock, whatever the app's says.
        var deadline = Instant.now().plus(settings.timeout());
        List<LockLogEntry> since = List.of();
        while (true) {
            // Between two reads of the log, a report ends the wait at once.
            var reported = unlatch.awaitOutcome(settings.interval());
            if (reported.isPresent()) {
                return result(reported.get());
            }
            try {
                since = readLog(ownerId, nukiSmartlockId, deadline).stream()
                        .filter(entry -> !entry.occurredAt().isBefore(requestedAt.minus(CLOCK_SKEW)))
                        .toList();
                var logged = since.stream()
                        .filter(LockLogEntry::isRemoteUnlatch)
                        .findFirst();
                if (logged.isPresent()) {
                    return result(logged.get().result());
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

    /**
     * Reads the lock's log, for no longer than what is left of the wait, and one interval at least: a read may
     * take Nuki's timeouts several times over, which would keep the guest, and those waiting for this opening,
     * long past the time the wait is meant to last. A read that takes longer is left to end by itself, and counts
     * as Nuki failing.
     */
    private List<LockLogEntry> readLog(UUID ownerId, long nukiSmartlockId, Instant deadline) {
        var left = Duration.between(Instant.now(), deadline);
        var patience = left.compareTo(settings.interval()) > 0 ? left : settings.interval();
        var read = CompletableFuture.supplyAsync(() -> lockProvider.getRecentLog(ownerId, nukiSmartlockId), READS);
        try {
            return read.get(patience.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockProviderException(LockProviderException.Reason.NUKI_ERROR, e);
        } catch (TimeoutException e) {
            throw new LockProviderException(LockProviderException.Reason.NUKI_ERROR, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof LockProviderException failure) {
                throw failure;
            }
            throw new LockProviderException(LockProviderException.Reason.NUKI_ERROR, e);
        }
    }

    private static AccessOutcome result(ActionResult state) {
        return switch (state) {
            case SUCCEEDED -> AccessOutcome.OPENED;
            case REJECTED_NIGHT_MODE -> AccessOutcome.NIGHT_MODE;
            case MOTOR_BLOCKED, FAILED -> AccessOutcome.LOCK_FAILED;
        };
    }
}
