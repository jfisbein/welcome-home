package welcomehome.notifications;

import org.jspecify.annotations.Nullable;
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
    /** A guest's link was refused: not valid yet, expired, revoked, outside its schedule, or too many attempts. */
    DENIED,
    /** Nuki rejected the owner's connection, or accepts it again. */
    DISCONNECTED,
    /** A lock's battery is critical, Nuki cannot reach it, or its motor is blocked. */
    LOCK_PROBLEMS;

    /**
     * The kind of an attempt, as {@link AccessOutcome#news()} says what it is to its owner.
     *
     * @return the kind, or null for an attempt no email is about: a guest asked to confirm where they are, or
     *         to type a PIN, whose mistake was caught
     */
    static @Nullable NotificationKind of(AccessOutcome outcome) {
        return switch (outcome.news()) {
            case OPENED -> OPENED;
            case FAILED -> FAILED;
            case DENIED -> DENIED;
            case NONE -> null;
        };
    }
}
