package welcomehome.notifications;

import welcomehome.audit.AccessResult;

/** The kinds of email an owner can choose to get. */
enum NotificationKind {

    /** A guest opened the door. */
    OPENED,
    /** A guest could not get in although their link was valid: the lock or Nuki failed, or it is unavailable. */
    FAILED,
    /** A guest's link was refused: not valid yet, expired, revoked, or too many attempts. */
    DENIED,
    /** Nuki rejected the owner's API token. */
    DISCONNECTED;

    /** The kind of an attempt, from its result and its outcome's code (see {@code access.OpenResult}). */
    static NotificationKind of(AccessResult result, String reason) {
        return switch (result) {
            case OK -> "opened".equals(reason) ? OPENED : FAILED;
            case ERROR -> FAILED;
            case DENIED -> "unavailable".equals(reason) ? FAILED : DENIED;
        };
    }
}
