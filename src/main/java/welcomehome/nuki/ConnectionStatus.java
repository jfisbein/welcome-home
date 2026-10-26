package welcomehome.nuki;

/**
 * Whether an owner's Nuki connection works.
 */
public enum ConnectionStatus {

    /** Nuki accepted the connection's token when it was last used or checked. */
    OK,
    /** Nuki rejected the connection's token: the owner has to connect Nuki again. */
    DISCONNECTED
}
