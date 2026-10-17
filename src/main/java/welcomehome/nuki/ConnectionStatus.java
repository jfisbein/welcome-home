package welcomehome.nuki;

/**
 * Whether an owner's Nuki connection works.
 */
public enum ConnectionStatus {

    /** Nuki accepted the API token when it was last used or checked. */
    OK,
    /** Nuki rejected the API token: the owner has to paste a new one. */
    DISCONNECTED
}
