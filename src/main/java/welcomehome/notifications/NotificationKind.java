package welcomehome.notifications;

import welcomehome.audit.AccessOutcome;

/** The kinds of email an owner can choose to get. */
enum NotificationKind {

    /** A guest opened the door. */
    OPENED,
    /**
     * A guest's link was valid, but the door did not open or the lock did not confirm it: the lock or Nuki failed,
     * or the door is unavailable.
     */
    FAILED,
    /** A guest's link was refused: not valid yet, expired, revoked, or too many attempts. */
    DENIED,
    /** Nuki rejected the owner's API token. */
    DISCONNECTED;

    /**
     * The kind of an attempt. An unavailable door is a failure, not a denial: the link was fine, and the owner is
     * the one who must act.
     */
    static NotificationKind of(AccessOutcome outcome) {
        return switch (outcome) {
            case OPENED -> OPENED;
            case NOT_CONFIRMED, UNAVAILABLE, NUKI_ERROR, LOCK_FAILED, NIGHT_MODE, MISSING_PERMISSION -> FAILED;
            case INVALID, TOO_MANY_ATTEMPTS, REVOKED, NOT_YET_VALID, EXPIRED -> DENIED;
        };
    }
}
