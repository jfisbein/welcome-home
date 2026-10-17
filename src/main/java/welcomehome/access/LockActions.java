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

/**
 * One action at a time per lock. A guest who presses "Abrir" while an opening of the same lock is under way
 * waits for it and gets its result, and Nuki receives a single action. Kept in memory, which is enough for
 * a single instance.
 */
@Component
class LockActions {

    /** Far longer than an action takes with Nuki's timeouts: only a stuck action makes anyone wait this long. */
    static final Duration MAX_WAIT = Duration.ofMinutes(1);

    private final ConcurrentMap<UUID, CompletableFuture<OpenResult>> inFlight = new ConcurrentHashMap<>();

    /**
     * Runs {@code action} for the lock, or, if one is already running, waits for it and returns its result.
     *
     * @return the result of the action that ran
     */
    OpenResult runOnce(UUID lockId, Supplier<OpenResult> action) {
        var mine = new CompletableFuture<OpenResult>();
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

    private static OpenResult await(CompletableFuture<OpenResult> running) {
        try {
            return running.get(MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OpenResult.NUKI_ERROR;
        } catch (ExecutionException | TimeoutException e) {
            return OpenResult.NUKI_ERROR;
        }
    }
}
