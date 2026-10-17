package welcomehome.nuki;

/**
 * What a lock log entry records.
 */
public enum LockAction {

    /** The lock was unlocked. */
    UNLOCK,
    /** The lock was locked. */
    LOCK,
    /** The lock was unlocked and the latch pulled. */
    UNLATCH,
    /** The door sensor saw the door open. */
    DOOR_OPENED,
    /** The door sensor saw the door close. */
    DOOR_CLOSED,
    /** Anything else, such as a lock'n'go or a door sensor alarm. */
    OTHER
}
