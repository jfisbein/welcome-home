package welcomehome.access;

import org.jspecify.annotations.Nullable;
import welcomehome.locks.LocationCheck;
import welcomehome.nuki.Coordinates;

/**
 * Whether a guest is in front of the door they slid to open, for a door that checks it. Against mistakes, such as
 * sliding at home to try the link, not against someone determined: a position can be faked. So only a guest
 * clearly far counts as far, their distance less the browser's accuracy beyond the radius; with a poor accuracy
 * the check gives the benefit of the doubt. A position too vague to say anything, as a computer placed by its IP
 * address has, with an accuracy of kilometres, is no position: it would put a guest at home within reach of any
 * door.
 */
final class DoorDistance {

    /** Earth's mean radius, in metres, which the great-circle distance takes the Earth to be a sphere of. */
    private static final double EARTH_RADIUS_M = 6_371_000;

    /**
     * An accuracy beyond this, and beyond the door's radius, makes a position unknown. A phone indoors is within
     * some tens of metres, or a few hundred by its cell alone; a computer placed by its IP address, kilometres.
     */
    static final double VAGUE_ACCURACY_M = 500;

    /** What the check decides. */
    enum Verdict {
        /** Open, as for any door. */
        OPEN,
        /** Ask the guest to confirm that they are in front of the door. */
        CONFIRM,
        /** Refuse: the door does not open for a guest far from it, or, in the strictest mode, for one it cannot place. */
        REFUSE
    }

    /**
     * What the check decided, and what the history keeps of it.
     *
     * @param distanceM from the guest to the door in metres, rounded to 10, or null without a position, or with
     *                  one too vague to tell
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
            return unknown(check, null, confirmed);
        }
        if (guest.accuracy() > Math.max(radiusM, VAGUE_ACCURACY_M)) {
            return unknown(check, wholeMetres(guest.accuracy()), confirmed);
        }
        var distance = metres(door, guest);
        var far = distance - guest.accuracy() > radiusM;
        var verdict = !far ? Verdict.OPEN
                : check == LocationCheck.CONFIRM_WHEN_FAR ? (confirmed ? Verdict.OPEN : Verdict.CONFIRM)
                : Verdict.REFUSE;
        return new Check(verdict, (int) Math.round(distance / 10) * 10, wholeMetres(guest.accuracy()),
                far && verdict == Verdict.OPEN);
    }

    /**
     * A guest whose position is unknown, or too vague to tell: asked to confirm, as nobody must be left at the door
     * for a phone that does not say, except by the one mode whose owner chose that.
     */
    private static Check unknown(LocationCheck check, @Nullable Integer accuracyM, boolean confirmed) {
        if (check == LocationCheck.REQUIRE_NEAR) {
            return new Check(Verdict.REFUSE, null, accuracyM, false);
        }
        return new Check(confirmed ? Verdict.OPEN : Verdict.CONFIRM, null, accuracyM, confirmed);
    }

    /** A browser may give any accuracy, far beyond what an int holds. */
    private static int wholeMetres(double metres) {
        return (int) Math.min(Math.round(metres), Integer.MAX_VALUE);
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
