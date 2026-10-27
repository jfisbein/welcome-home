package welcomehome.locks;

import java.time.ZoneId;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;
import welcomehome.nuki.ConnectionStatus;
import welcomehome.nuki.LockProvider;
import welcomehome.nuki.LockProviderException;
import welcomehome.nuki.LockProviderException.Reason;
import welcomehome.nuki.NukiConnections;

/**
 * Brings an owner's locks, smart locks and Openers, in line with their Nuki account. New locks are imported with
 * their type, their Nuki name and time zone (the server's, if Nuki reports none), which the owner can change;
 * locks no longer in the account become inactive but keep their links; locks that come back become active again.
 */
@Service
class LockSync {

    private final LockProvider lockProvider;
    private final NukiConnections nukiConnections;
    private final LockDao locks;
    private final OwnerTransactions ownerTransactions;

    LockSync(LockProvider lockProvider, NukiConnections nukiConnections, LockDao locks,
             OwnerTransactions ownerTransactions) {
        this.lockProvider = lockProvider;
        this.nukiConnections = nukiConnections;
        this.locks = locks;
        this.ownerTransactions = ownerTransactions;
    }

    /**
     * @throws LockProviderException if the owner has no working Nuki connection or Nuki cannot list the locks
     */
    SyncResult sync(UUID ownerId) {
        var connection = nukiConnections.find(ownerId)
                .filter(found -> found.status() == ConnectionStatus.OK)
                .orElseThrow(() -> new LockProviderException(Reason.NOT_CONNECTED));
        // Outside the transaction: no database connection is held while waiting for Nuki.
        var smartLocks = lockProvider.listLocks(ownerId);
        var serverTimeZone = ZoneId.systemDefault().getId();
        return ownerTransactions.execute(ownerId, () -> {
            var known = locks.findAll(ownerId).stream()
                    .collect(Collectors.toMap(OwnerLock::nukiSmartlockId, lock -> lock));
            var inNuki = smartLocks.stream().map(smartLock -> smartLock.smartlockId()).collect(Collectors.toSet());
            int added = 0;
            for (var smartLock : smartLocks) {
                if (!known.containsKey(smartLock.smartlockId())) {
                    added++;
                }
                var timeZone = smartLock.timeZone() != null ? smartLock.timeZone() : serverTimeZone;
                locks.upsert(ownerId, connection.id(), smartLock.smartlockId(), smartLock.type(), smartLock.name(),
                        timeZone);
            }
            int deactivated = 0;
            for (var lock : known.values()) {
                if (lock.active() && !inNuki.contains(lock.nukiSmartlockId())) {
                    locks.deactivate(ownerId, lock.id());
                    deactivated++;
                }
            }
            return new SyncResult(added, deactivated);
        });
    }

    /**
     * @param added       locks imported for the first time
     * @param deactivated locks that are no longer in the Nuki account
     */
    record SyncResult(int added, int deactivated) {
    }
}
