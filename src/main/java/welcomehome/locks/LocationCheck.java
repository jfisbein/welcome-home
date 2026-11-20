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
     * Only a guest whose position is known and near opens: one clearly far cannot, and neither can one whose
     * position is unknown or too vague to tell, who is told to let the page use their location. The owner accepts
     * that such a guest stays at the door until they open for them. Stored as {@code REFUSE_WHEN_FAR} with
     * {@code lock.location_required}: the name of a mode between the two, taken out on 2026-11-20, that refused a
     * guest clearly far and let one without a position open by confirming, so that denying the permission was the
     * way around it.
     */
    REQUIRE_NEAR
}
