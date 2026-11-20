package welcomehome.nuki;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * {@link LockProvider} over the Nuki Web API, with each owner's stored API token or OAuth2 access token.
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
    public Optional<LockConnection> findConnection(UUID ownerId) {
        return connections.find(ownerId).map(connection -> new LockConnection(connection.id(), connection.status()));
    }

    @Override
    public List<NukiDevice> listLocks(UUID ownerId) {
        var locks = connections.callNuki(ownerId, nuki::listLocks);
        connections.accepted(ownerId);
        return locks;
    }

    @Override
    public Map<Long, LockSnapshot> listSnapshots(UUID ownerId) {
        return connections.callNuki(ownerId, nuki::listSnapshots);
    }

    @Override
    public LockSnapshot getSnapshot(UUID ownerId, long smartlockId) {
        return connections.callNuki(ownerId, accessToken -> nuki.getSnapshot(accessToken, smartlockId));
    }

    @Override
    public void unlatch(UUID ownerId, long smartlockId) {
        connections.callNuki(ownerId, accessToken -> {
            nuki.unlatch(accessToken, smartlockId);
            return true;
        });
    }

    @Override
    public List<LockLogEntry> getRecentLog(UUID ownerId, long smartlockId) {
        return connections.callNuki(ownerId, accessToken -> nuki.getRecentLog(accessToken, smartlockId));
    }
}
