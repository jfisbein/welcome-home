package welcomehome.nuki;

/**
 * How an action in a lock's log ended.
 */
public enum LockActionState {

    /** The action completed. */
    SUCCEEDED,
    /** The motor was blocked, for example by a bolt that did not go in or out. */
    MOTOR_BLOCKED,
    /** The lock is in night mode and refused the action. */
    REJECTED_NIGHT_MODE,
    /** Any other failure: canceled, busy, low battery, and so on. */
    FAILED
}
