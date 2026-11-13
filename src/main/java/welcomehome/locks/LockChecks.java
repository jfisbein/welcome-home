package welcomehome.locks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import welcomehome.locks.LockReachability.Connection;
import welcomehome.nuki.LockProvider;
import welcomehome.nuki.LockProviderException;
import welcomehome.nuki.LockSnapshot;
import welcomehome.nuki.LockPosition;

/**
 * Tells what is wrong with an owner's locks, for the pages that list them. Those pages wait for Nuki only for a
 * moment: a slow Nuki must not keep an owner from their home page, whose other contents do not depend on it.
 */
@Service
public class LockChecks {

    private static final Logger log = LoggerFactory.getLogger(LockChecks.class);
    private static final Duration PATIENCE = Duration.ofSeconds(2);
    private static final LockWarnings NONE = new LockWarnings(Map.of(), false);
    private static final LockWarnings UNKNOWN = new LockWarnings(Map.of(), true);

    private final LockProvider lockProvider;
    private final LockReachability reachability;
    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    LockChecks(LockProvider lockProvider, LockReachability reachability) {
        this.lockProvider = lockProvider;
        this.reachability = reachability;
    }

    /**
     * Asks Nuki about the owner's active locks, in one request, and says what is wrong with them. It never fails:
     * without a working connection there are no warnings, which the home page explains in its own way, and when
     * Nuki does not answer in time the result says that it is unknown.
     *
     * @param ownerId the owner
     * @param locks   the owner's locks
     * @return what is wrong with them
     */
    public LockWarnings warnings(UUID ownerId, List<OwnerLock> locks) {
        var active = locks.stream().filter(OwnerLock::active).toList();
        if (active.isEmpty()) {
            return NONE;
        }
        Map<Long, LockSnapshot> states;
        try {
            states = CompletableFuture.supplyAsync(() -> lockProvider.listSnapshots(ownerId), executor)
                    .get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UNKNOWN;
        } catch (ExecutionException e) {
            return failed(e.getCause());
        }
        var byLock = new HashMap<UUID, List<LockWarning>>();
        for (var lock : active) {
            var state = states.get(lock.nukiSmartlockId());
            if (state != null) {
                var warnings = warningsOf(lock, state, reachability.observe(ownerId, lock.id(), state.online()));
                if (!warnings.isEmpty()) {
                    byLock.put(lock.id(), warnings);
                }
            }
        }
        return new LockWarnings(byLock, false);
    }

    private static List<LockWarning> warningsOf(OwnerLock lock, LockSnapshot state, Connection connection) {
        var warnings = new ArrayList<LockWarning>();
        if (connection == Connection.OFFLINE) {
            warnings.add(LockWarning.OFFLINE);
        }
        if (state.batteryCritical()) {
            warnings.add(LockWarning.BATTERY_CRITICAL);
        }
        if (state.position() == LockPosition.MOTOR_BLOCKED) {
            warnings.add(LockWarning.MOTOR_BLOCKED);
        }
        if (!lock.opener() && !state.autoLockEnabled() && !lock.relockingHandledElsewhere()) {
            warnings.add(LockWarning.AUTO_LOCK_OFF);
        }
        return warnings;
    }

    private static LockWarnings failed(@Nullable Throwable cause) {
        if (cause instanceof LockProviderException failure) {
            return switch (failure.reason()) {
                case NOT_CONNECTED, ACCESS_DENIED -> NONE;
                case MISSING_PERMISSION, LOCK_NOT_FOUND, NUKI_ERROR, NO_ANSWER -> UNKNOWN;
            };
        }
        log.warn("Could not read the state of an owner's locks", cause);
        return UNKNOWN;
    }
}
