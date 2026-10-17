package welcomehome.nuki;

import org.jspecify.annotations.Nullable;

/**
 * A lock's state as last reported to Nuki.
 *
 * @param status          the position of the lock
 * @param online          whether the lock is reachable from Nuki's servers
 * @param batteryCritical whether the battery needs replacing or charging now
 * @param batteryCharge   the battery level in percent, or {@code null} if the lock does not report it
 * @param autoLockEnabled whether the lock locks itself again: without it, an unlatched door stays
 *                        unlocked until someone locks it
 */
public record LockState(LockStatus status, boolean online, boolean batteryCritical, @Nullable Integer batteryCharge,
                        boolean autoLockEnabled) {
}
