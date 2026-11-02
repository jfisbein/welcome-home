package welcomehome.locks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;
import welcomehome.nuki.LockPosition;
import welcomehome.nuki.LockProvider;
import welcomehome.nuki.LockSnapshot;

/**
 * Finds the problems that need the owner's hands at a lock, a battery to change, a lock Nuki cannot reach or a
 * blocked motor, and tells of each when it starts, not every day it lasts. Auto Lock off is not one: it is a setting
 * the owner chose.
 */
@Service
class LockProblems {

    private static final Set<LockWarning> PROBLEMS =
            EnumSet.of(LockWarning.OFFLINE, LockWarning.BATTERY_CRITICAL, LockWarning.MOTOR_BLOCKED);

    private final LockProvider lockProvider;
    private final LockDao locks;
    private final OwnerTransactions ownerTransactions;
    private final ApplicationEventPublisher events;

    LockProblems(LockProvider lockProvider, LockDao locks, OwnerTransactions ownerTransactions,
                 ApplicationEventPublisher events) {
        this.lockProvider = lockProvider;
        this.locks = locks;
        this.ownerTransactions = ownerTransactions;
        this.events = events;
    }

    /**
     * Reads every active lock of the owner from Nuki, in one request, and records its problems, publishing
     * {@link LockProblemsFound} for those that started. A lock Nuki did not list keeps what it had.
     *
     * @throws welcomehome.nuki.LockProviderException if Nuki cannot tell
     */
    void check(UUID ownerId) {
        var active = ownerTransactions.execute(ownerId, () -> locks.findAll(ownerId)).stream()
                .filter(OwnerLock::active).toList();
        if (active.isEmpty()) {
            return;
        }
        // Outside the transaction: no database connection is held while waiting for Nuki.
        var snapshots = lockProvider.listSnapshots(ownerId);
        ownerTransactions.run(ownerId, () -> {
            for (var lock : active) {
                var snapshot = snapshots.get(lock.nukiSmartlockId());
                if (snapshot != null) {
                    record(ownerId, lock, problemsOf(snapshot));
                }
            }
        });
    }

    private void record(UUID ownerId, OwnerLock lock, Set<LockWarning> now) {
        var before = parse(locks.findNotifiedProblems(ownerId, lock.id()));
        if (now.equals(before)) {
            return;
        }
        locks.saveNotifiedProblems(ownerId, lock.id(), now.isEmpty() ? null
                : now.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        var started = new ArrayList<>(now);
        started.removeAll(before);
        if (!started.isEmpty()) {
            started.sort(null);
            events.publishEvent(new LockProblemsFound(ownerId, lock.id(), lock.name(), List.copyOf(started)));
        }
    }

    static Set<LockWarning> problemsOf(LockSnapshot snapshot) {
        var problems = EnumSet.noneOf(LockWarning.class);
        if (!snapshot.online()) {
            problems.add(LockWarning.OFFLINE);
        }
        if (snapshot.batteryCritical()) {
            problems.add(LockWarning.BATTERY_CRITICAL);
        }
        if (snapshot.position() == LockPosition.MOTOR_BLOCKED) {
            problems.add(LockWarning.MOTOR_BLOCKED);
        }
        return problems;
    }

    private static Set<LockWarning> parse(@Nullable String stored) {
        var problems = EnumSet.noneOf(LockWarning.class);
        if (stored != null && !stored.isBlank()) {
            Arrays.stream(stored.split(",")).map(String::strip)
                    .filter(name -> Arrays.stream(LockWarning.values()).anyMatch(w -> w.name().equals(name)))
                    .map(LockWarning::valueOf).filter(PROBLEMS::contains).forEach(problems::add);
        }
        return problems;
    }
}
