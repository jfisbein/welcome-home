package welcomehome.nuki;


/**
 * An owner's connection to their locks, whatever the way to reach them.
 *
 * @param status whether it works
 */
public record LockConnection(ConnectionStatus status) {

    /**
     * Tells whether the connection works.
     *
     * @return whether its status is {@link ConnectionStatus#OK}
     */
    public boolean works() {
        return status == ConnectionStatus.OK;
    }
}
