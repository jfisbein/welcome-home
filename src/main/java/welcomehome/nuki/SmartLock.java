package welcomehome.nuki;

import org.jspecify.annotations.Nullable;

/**
 * A smart lock or an Opener in an owner's Nuki account.
 *
 * @param smartlockId the device's Nuki id
 * @param type        whether it is a smart lock or an Opener
 * @param name        the device's name in the Nuki app
 * @param timeZone    the device's time zone as set in the Nuki app, an IANA id such as {@code Europe/Berlin},
 *                    or {@code null} if the device reports none
 */
public record SmartLock(long smartlockId, DeviceType type, String name, @Nullable String timeZone) {
}
