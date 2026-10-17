package welcomehome.nuki;

/**
 * The position of a lock.
 */
public enum LockStatus {

    /** Locked. */
    LOCKED,
    /** Unlocked, but not unlatched. */
    UNLOCKED,
    /** Unlocked with the latch pulled: the door opens with a push. */
    UNLATCHED,
    /** Locking, unlocking or unlatching right now. */
    MOVING,
    /** The motor is blocked, for example by a bolt that does not go in. */
    MOTOR_BLOCKED,
    /** Not calibrated, or a state this app does not know. */
    UNKNOWN
}
