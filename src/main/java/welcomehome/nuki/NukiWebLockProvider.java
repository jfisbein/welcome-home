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
        return connections.callNuki(ownerId, nuki::listLocks);
    }

    @Override
    public LockState getState(UUID ownerId, long smartlockId) {
        return connections.callNuki(ownerId, apiToken -> nuki.getState(apiToken, smartlockId));
    }

    @Override
    public void unlatch(UUID ownerId, long smartlockId) {
        connections.callNuki(ownerId, apiToken -> {
            nuki.unlatch(apiToken, smartlockId);
            return null;
        });
    }

    @Override
    public List<LockLogEntry> getRecentLog(UUID ownerId, long smartlockId) {
        return connections.callNuki(ownerId, apiToken -> nuki.getRecentLog(apiToken, smartlockId));
    }
}
