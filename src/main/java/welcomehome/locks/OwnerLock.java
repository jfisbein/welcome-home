package welcomehome.locks;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import welcomehome.nuki.Coordinates;
import welcomehome.nuki.DeviceType;

/**
 * One of an owner's locks: a smart lock, or an Opener that opens a building's entrance.
 *
 * @param id              the lock's id in welcome-home
 * @param nukiSmartlockId the lock's id in Nuki
 * @param name            the name the owner gives it; on import, its name in the Nuki app
 * @param timeZone        the time zone in which the lock's links are entered and shown
 * @param active          false when the lock is no longer in the owner's Nuki account
 * @param relockingHandledElsewhere whether the owner relocks the door some other way than Nuki's Auto Lock,
 *                        so that the panel does not warn that Auto Lock is off
 * @param deviceType      whether it is a smart lock or an Opener
 * @param entranceLockId  the owner's Opener at the building's entrance, which the lock's links open too, or
 *                        {@code null}; only a smart lock has one
 * @param guestInstructions what the owner tells the lock's guests on its links' page, such as the address or
 *                        where a key is, or {@code null} for nothing
 * @param latitude        where the lock is, with {@code longitude}, or {@code null} with it; see {@link #coordinates()}
 * @param longitude       where the lock is, with {@code latitude}
 */
public record OwnerLock(UUID id, long nukiSmartlockId, String name, String timeZone, boolean active,
                        boolean relockingHandledElsewhere, DeviceType deviceType, @Nullable UUID entranceLockId,
                        @Nullable String guestInstructions, @Nullable Double latitude, @Nullable Double longitude) {

    /**
     * Where the lock is, from Nuki or from its owner. Only the owner sees it: a guest's page must not tell where
     * the door is.
     *
     * @return its coordinates, or {@code null} if it has none
     */
    public @Nullable Coordinates coordinates() {
        return latitude == null || longitude == null ? null : new Coordinates(latitude, longitude);
    }

    /**
     * Tells whether the lock is an Opener.
     *
     * @return whether {@link #deviceType()} is {@link DeviceType#OPENER}
     */
    public boolean opener() {
        return deviceType == DeviceType.OPENER;
    }
}
