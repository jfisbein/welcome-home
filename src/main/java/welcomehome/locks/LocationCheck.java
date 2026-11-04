package welcomehome.locks;

/**
 * Whether a lock checks how far a guest is from the door before opening, and what it does when they are far: a
 * guest's position can be faked, so this catches mistakes, such as sliding at home to try the link, not someone
 * determined.
 */
public enum LocationCheck {

    /** No check. */
    OFF,
    /** A guest clearly far, or whose position is unknown, may open after confirming. */
    CONFIRM_WHEN_FAR,
    /**
     * A guest clearly far cannot open; one whose position is unknown may after confirming, as a guest who refused
     * the permission must not be left at the door.
     */
    REFUSE_WHEN_FAR
}
