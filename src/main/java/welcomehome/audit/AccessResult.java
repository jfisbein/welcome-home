package welcomehome.audit;

/**
 * What happened at the door when a guest tried to open it, which the history keeps with each attempt. See
 * {@link AccessOutcome} for what it came to.
 */
public enum AccessResult {

    /** The lock was asked to open: it did, or, without its confirmation, it may have. */
    OK,
    /** The app refused: the link was revoked, out of its dates, rate limited, and so on. */
    DENIED,
    /** The app tried, and Nuki or the lock failed or refused. */
    ERROR
}
