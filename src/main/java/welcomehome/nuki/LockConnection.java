package welcomehome.nuki;

import java.util.UUID;

/**
 * An owner's connection to their locks, whatever the way to reach them.
 *
 * @param id     the connection's id, which locks refer to
 * @param status whether it works
 */
public record LockConnection(UUID id, ConnectionStatus status) {

    /**
     * Tells whether the connection works.
     *
     * @return whether its status is {@link ConnectionStatus#OK}
     */
    public boolean works() {
        return status == ConnectionStatus.OK;
    }
}
