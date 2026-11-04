package welcomehome.access;

import org.jspecify.annotations.Nullable;
import welcomehome.locks.LocationCheck;
import welcomehome.nuki.Coordinates;

/**
 * Whether a guest is in front of the door they slid to open, for a door that checks it. Against mistakes, such as
 * sliding at home to try the link, not against someone determined: a position can be faked. So only a guest
 * clearly far counts as far, their distance less the browser's accuracy beyond the radius; with a poor accuracy
 * the check gives the benefit of the doubt.
 */
final class DoorDistance {

    /** Earth's mean radius, in metres, which the great-circle distance takes the Earth to be a sphere of. */
    private static final double EARTH_RADIUS_M = 6_371_000;

    /** What the check decides. */
    enum Verdict {
        /** Open, as for any door. */
        OPEN,
        /** Ask the guest to confirm that they are in front of the door. */
        CONFIRM,
        /** Refuse: the door does not open for a guest far from it. */
        REFUSE
    }

    /**
     * What the check decided, and what the history keeps of it.
     *
     * @param distanceM from the guest to the door in metres, rounded to 10, or null without a position
     * @param accuracyM the browser's accuracy in metres, or null without a position
     * @param byConfirmation whether the door opens only because the guest confirmed being in front of it, while
     *                  they seemed far or their position was unknown
     */
    record Check(Verdict verdict, @Nullable Integer distanceM, @Nullable Integer accuracyM, boolean byConfirmation) {
    }

    private DoorDistance() {
    }

    /**
     * @param check     how the door checks; not {@link LocationCheck#OFF}
     * @param radiusM   how far from the door a guest still counts as in front of it
     * @param door      where the door is
     * @param guest     where the guest's browser says they are, or null if it did not say
     * @param confirmed whether the guest confirmed being in front of the door
     */
    static Check check(LocationCheck check, int radiusM, Coordinates door, @Nullable GuestPosition guest,
                       boolean confirmed) {
        if (guest == null) {
            return new Check(confirmed ? Verdict.OPEN : Verdict.CONFIRM, null, null, confirmed);
        }
        var distance = metres(door, guest);
        var far = distance - guest.accuracy() > radiusM;
        var verdict = !far ? Verdict.OPEN
                : check == LocationCheck.REFUSE_WHEN_FAR ? Verdict.REFUSE
                : confirmed ? Verdict.OPEN : Verdict.CONFIRM;
        return new Check(verdict, (int) Math.round(distance / 10) * 10, (int) Math.round(guest.accuracy()),
                far && verdict == Verdict.OPEN);
    }

    /** The great-circle distance, by the haversine formula: within a few metres over a town. */
    static double metres(Coordinates door, GuestPosition guest) {
        var latitude1 = Math.toRadians(door.latitude());
        var latitude2 = Math.toRadians(guest.latitude());
        var latitudes = latitude2 - latitude1;
        var longitudes = Math.toRadians(guest.longitude() - door.longitude());
        var haversine = Math.pow(Math.sin(latitudes / 2), 2)
                + Math.cos(latitude1) * Math.cos(latitude2) * Math.pow(Math.sin(longitudes / 2), 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(haversine)));
    }
}
