package welcomehome.audit;

/**
 * The outcome of a guest's attempt to open a door.
 */
public enum AccessResult {

    /** Nuki accepted the unlatch action. */
    OK,
    /** The app refused: the link was revoked, out of its dates, rate limited, and so on. */
    DENIED,
    /** The app tried, but Nuki failed or did not answer. */
    ERROR
}
