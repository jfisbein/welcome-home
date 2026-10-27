package welcomehome.nuki;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * {@link LockProvider} over the Nuki Web API, with each owner's stored API token or OAuth2 access token.
 *
 * <p>An owner who authorized welcome-home's OAuth2 client unlatches with Nuki's Advanced API, which reports the
 * outcome with a webhook ({@link NukiWebhookController}); if Nuki refuses that, the plain action is sent instead, as
 * it ran nothing. An API token is the owner's own, whose webhooks would go to the owner's own webhook URL, if any:
 * it unlatches with the plain action, whose outcome only the lock's log tells.
 */
@Component
class NukiWebLockProvider implements LockProvider {

    private final NukiConnections connections;
    private final NukiWebClient nuki;
    private final ActionReports reports;

    NukiWebLockProvider(NukiConnections connections, NukiWebClient nuki, ActionReports reports) {
        this.connections = connections;
        this.nuki = nuki;
        this.reports = reports;
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
    public PendingAction unlatch(UUID ownerId, long smartlockId) {
        return connections.callNukiWithCredential(ownerId, (credential, accessToken) -> {
            if (credential == NukiCredential.OAUTH2) {
                var requestId = nuki.unlatchReported(accessToken, smartlockId);
                if (requestId.isPresent()) {
                    return reports.pending(requestId.get());
                }
            }
            nuki.unlatch(accessToken, smartlockId);
            return PendingAction.UNREPORTED;
        });
    }

    @Override
    public List<LockLogEntry> getRecentLog(UUID ownerId, long smartlockId) {
        return connections.callNuki(ownerId, apiToken -> nuki.getRecentLog(apiToken, smartlockId));
    }
}
