package welcomehome.locks;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;
import welcomehome.nuki.ConnectionStatus;
import welcomehome.nuki.LockProvider;
import welcomehome.nuki.LockProviderException;
import welcomehome.nuki.LockProviderException.Reason;
import welcomehome.nuki.NukiConnected;
import welcomehome.nuki.NukiConnections;
import welcomehome.nuki.NukiDevice;

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
        return sync(ownerId, false);
    }

    /**
     * Syncs as {@link #sync} does, but without the owner watching: a list with no locks at all changes nothing, as
     * an owner who has locks is more likely to meet a mistake of Nuki's than to have removed them all, and marking
     * them inactive would stop every link. The owner can still ask for it with {@link #sync}.
     *
     * @throws LockProviderException as {@link #sync} does
     */
    SyncResult syncDaily(UUID ownerId) {
        return sync(ownerId, true);
    }

    private SyncResult sync(UUID ownerId, boolean keepLocksIfNoneListed) {
        var connection = nukiConnections.find(ownerId)
                .filter(found -> found.status() == ConnectionStatus.OK)
                .orElseThrow(() -> new LockProviderException(Reason.NOT_CONNECTED));
        // Outside the transaction: no database connection is held while waiting for Nuki.
        var devices = lockProvider.listLocks(ownerId);
        return apply(ownerId, connection.id(), devices, keepLocksIfNoneListed);
    }

    /** Imports what Nuki has just listed: an owner who connects it should not have to ask for that. */
    @EventListener
    void on(NukiConnected connected) {
        nukiConnections.find(connected.ownerId()).ifPresent(connection ->
                apply(connected.ownerId(), connection.id(), connected.devices(), true));
    }

    private SyncResult apply(UUID ownerId, UUID connectionId, List<NukiDevice> devices,
                             boolean keepLocksIfNoneListed) {
        if (devices.isEmpty() && keepLocksIfNoneListed) {
            return new SyncResult(0, 0);
        }
        var serverTimeZone = ZoneId.systemDefault().getId();
        return ownerTransactions.execute(ownerId, () -> {
            var known = locks.findAll(ownerId).stream()
                    .collect(Collectors.toMap(OwnerLock::nukiSmartlockId, lock -> lock));
            var inNuki = devices.stream().map(device -> device.smartlockId()).collect(Collectors.toSet());
            int added = 0;
            for (var device : devices) {
                if (!known.containsKey(device.smartlockId())) {
                    added++;
                }
                var timeZone = device.timeZone() != null ? device.timeZone() : serverTimeZone;
                locks.upsert(ownerId, connectionId, device.smartlockId(), device.type(), device.name(),
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
