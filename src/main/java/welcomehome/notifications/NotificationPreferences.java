package welcomehome.notifications;

/**
 * Which emails an owner gets.
 *
 * @param opened       when a guest opens the door
 * @param failed       when a guest with a valid link cannot get in
 * @param denied       when a guest's link is refused
 * @param disconnected when Nuki rejects the owner's API token
 */
record NotificationPreferences(boolean opened, boolean failed, boolean denied, boolean disconnected) {

    /** What an owner who never chose gets: everything. */
    static final NotificationPreferences ALL = new NotificationPreferences(true, true, true, true);

    boolean wants(NotificationKind kind) {
        return switch (kind) {
            case OPENED -> opened;
            case FAILED -> failed;
            case DENIED -> denied;
            case DISCONNECTED -> disconnected;
        };
    }
}
