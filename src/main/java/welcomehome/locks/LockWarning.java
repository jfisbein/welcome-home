package welcomehome.locks;

/**
 * Something wrong with a lock that its owner should hear of without opening its page.
 */
public enum LockWarning {
    /** Nuki's servers cannot reach it, so links cannot open it. */
    OFFLINE,
    /** Its battery needs replacing or charging now. */
    BATTERY_CRITICAL,
    /** Its motor is blocked. */
    MOTOR_BLOCKED
}
