package welcomehome.nuki;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * {@link LockProvider} over the Nuki Web API, with each owner's stored API token.
 */
@Component
class NukiWebLockProvider implements LockProvider {

    private final NukiConnections connections;
    private final NukiWebClient nuki;

    NukiWebLockProvider(NukiConnections connections, NukiWebClient nuki) {
        this.connections = connections;
        this.nuki = nuki;
    }

    @Override
    public List<SmartLock> listLocks(UUID ownerId) {
        return nuki.listLocks(connections.apiToken(ownerId));
    }

    @Override
    public LockState getState(UUID ownerId, long smartlockId) {
        return nuki.getState(connections.apiToken(ownerId), smartlockId);
    }

    @Override
    public void unlatch(UUID ownerId, long smartlockId) {
        nuki.unlatch(connections.apiToken(ownerId), smartlockId);
    }

    @Override
    public List<LockLogEntry> getRecentLog(UUID ownerId, long smartlockId) {
        return nuki.getRecentLog(connections.apiToken(ownerId), smartlockId);
    }
}
