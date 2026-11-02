package welcomehome.notifications;

/**
 * Which emails an owner gets.
 *
 * @param opened       when a guest opens the door
 * @param failed       when a guest with a valid link cannot get in
 * @param denied       when a guest's link is refused
 * @param disconnected when Nuki rejects the owner's connection, and when it accepts it again
 * @param lockProblems when a lock's battery is critical, Nuki cannot reach it or its motor is blocked
 */
record NotificationPreferences(boolean opened, boolean failed, boolean denied, boolean disconnected,
                               boolean lockProblems) {

    /** What an owner who never chose gets: everything. */
    static final NotificationPreferences ALL = new NotificationPreferences(true, true, true, true, true);

    boolean wants(NotificationKind kind) {
        return switch (kind) {
            case OPENED -> opened;
            case FAILED -> failed;
            case DENIED -> denied;
            case DISCONNECTED -> disconnected;
            case LOCK_PROBLEMS -> lockProblems;
        };
    }
}
