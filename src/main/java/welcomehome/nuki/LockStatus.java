package welcomehome.nuki;

/**
 * The position of a lock, or what an Opener is doing.
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
    /** An Opener that is online and opens the entrance when asked. */
    READY,
    /** An Opener with Ring to Open on: the entrance opens when someone rings the intercom. */
    RING_TO_OPEN,
    /** An Opener opening the entrance right now. */
    OPENING,
    /** Not calibrated, an Opener not set up, or a state this app does not know. */
    UNKNOWN
}
