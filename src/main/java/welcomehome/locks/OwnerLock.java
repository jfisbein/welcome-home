package welcomehome.locks;

import java.util.UUID;

/**
 * One of an owner's locks.
 *
 * @param id              the lock's id in welcome-home
 * @param nukiSmartlockId the lock's id in Nuki
 * @param name            the name the owner gives it; on import, its name in the Nuki app
 * @param timeZone        the time zone in which the lock's links are entered and shown
 * @param active          false when the lock is no longer in the owner's Nuki account
 */
public record OwnerLock(UUID id, long nukiSmartlockId, String name, String timeZone, boolean active) {
}
