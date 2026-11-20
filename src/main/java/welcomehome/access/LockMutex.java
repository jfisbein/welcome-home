package welcomehome.access;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import welcomehome.audit.AccessOutcome;

/**
 * One action at a time per lock. A guest who slides to open while an opening of the same lock is under way
 * waits for it and gets its result, and Nuki receives a single action. Kept in memory, which is enough for
 * a single instance.
 */
@Component
class LockMutex {

    /**
     * Longer than an action takes with Nuki's timeouts, now that each read of the lock's log is bound by what is
     * left of the confirmation's wait: only a stuck action makes anyone wait this long.
     */
    static final Duration MAX_WAIT = Duration.ofMinutes(1);

    private final ConcurrentMap<UUID, CompletableFuture<AccessOutcome>> inFlight = new ConcurrentHashMap<>();
    private final Duration maxWait;

    LockMutex() {
        this(MAX_WAIT);
    }

    /** With another wait, for a test that does not have a minute. */
    LockMutex(Duration maxWait) {
        this.maxWait = maxWait;
    }

    /**
     * Runs {@code action} for the lock, or, if one is already running, waits for it and returns its result.
     *
     * @return the result of the action that ran; {@link AccessOutcome#NOT_CONFIRMED} for a guest who waited for
     * an action that threw or that did not end in time, as it may have opened the door, or may yet: told that it
     * failed, they would slide again at once, and open twice
     */
    AccessOutcome runOnce(UUID lockId, Supplier<AccessOutcome> action) {
        var mine = new CompletableFuture<AccessOutcome>();
        var running = inFlight.putIfAbsent(lockId, mine);
        if (running != null) {
            return await(running);
        }
        try {
            var result = action.get();
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(lockId, mine);
        }
    }

    private AccessOutcome await(CompletableFuture<AccessOutcome> running) {
        try {
            return running.get(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AccessOutcome.NOT_CONFIRMED;
        } catch (ExecutionException | TimeoutException e) {
            return AccessOutcome.NOT_CONFIRMED;
        }
    }
}
