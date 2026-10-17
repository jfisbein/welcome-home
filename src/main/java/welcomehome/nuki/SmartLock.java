package welcomehome.nuki;

import org.jspecify.annotations.Nullable;

/**
 * A smart lock in an owner's Nuki account.
 *
 * @param smartlockId the lock's Nuki id
 * @param name        the lock's name in the Nuki app
 * @param timeZone    the lock's time zone as set in the Nuki app, an IANA id such as {@code Europe/Berlin},
 *                    or {@code null} if the lock reports none
 */
public record SmartLock(long smartlockId, String name, @Nullable String timeZone) {
}
