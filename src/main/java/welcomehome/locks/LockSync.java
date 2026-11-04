package welcomehome.locks;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;
import welcomehome.nuki.LockConnection;
import welcomehome.nuki.LockProvider;
import welcomehome.nuki.LockProviderException;
import welcomehome.nuki.LockProviderException.Reason;
import welcomehome.nuki.NukiConnected;
import welcomehome.nuki.NukiDevice;

/**
 * Brings an owner's locks, smart locks and Openers, in line with their Nuki account. New locks are imported with
 * their type, their Nuki name and time zone (the server's, if Nuki reports none or one that keeps its time), which
 * the owner can change;
 * locks no longer in the account become inactive but keep their links; locks that come back become active again.
 */
@Service
class LockSync {

    private final LockProvider lockProvider;
    private final LockDao locks;
    private final OwnerTransactions ownerTransactions;

    LockSync(LockProvider lockProvider, LockDao locks,
             OwnerTransactions ownerTransactions) {
        this.lockProvider = lockProvider;
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
        var connection = lockProvider.findConnection(ownerId)
                .filter(LockConnection::works)
                .orElseThrow(() -> new LockProviderException(Reason.NOT_CONNECTED));
        // Outside the transaction: no database connection is held while waiting for Nuki.
        var devices = lockProvider.listLocks(ownerId);
        return apply(ownerId, connection.id(), devices, keepLocksIfNoneListed);
    }

    /** Imports what Nuki has just listed: an owner who connects it should not have to ask for that. */
    @EventListener
    void on(NukiConnected connected) {
        lockProvider.findConnection(connected.ownerId()).ifPresent(connection ->
                apply(connected.ownerId(), connection.id(), connected.devices(), true));
    }

    private SyncResult apply(UUID ownerId, UUID connectionId, List<NukiDevice> devices,
                             boolean keepLocksIfNoneListed) {
        if (devices.isEmpty() && keepLocksIfNoneListed) {
            return new SyncResult(0, 0);
        }
        var serverTimeZone = ZoneId.systemDefault();
        var now = Instant.now();
        return ownerTransactions.execute(ownerId, () -> {
            var known = locks.findAll(ownerId).stream()
                    .collect(Collectors.toMap(OwnerLock::nukiSmartlockId, lock -> lock));
            var inNuki = devices.stream().map(device -> device.smartlockId()).collect(Collectors.toSet());
            int added = 0;
            for (var device : devices) {
                if (!known.containsKey(device.smartlockId())) {
                    added++;
                }
                var timeZone = importedTimeZone(device.timeZone(), serverTimeZone, now);
                locks.upsert(ownerId, connectionId, device.smartlockId(), device.type(), device.name(),
                        timeZone, device.coordinates());
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

    /**
     * The zone a new lock is imported with. Nuki covers each region with one zone, so a lock in Madrid reports
     * Europe/Berlin: when the zone reported keeps the server's time, the server's is taken instead, as owners are
     * most likely where the server is, and its name is the one they know ("hora de España peninsular", not "hora de
     * Europa central"). A lock elsewhere in the region gets it too, and its owner can change it.
     *
     * @param reported the zone Nuki reports, or null if none
     * @param server   the server's time zone
     * @param now      from when the two zones must keep the same time
     * @return the zone's id
     */
    static String importedTimeZone(@Nullable String reported, ZoneId server, Instant now) {
        if (reported == null || keepTheSameTime(ZoneId.of(reported), server, now)) {
            return server.getId();
        }
        return reported;
    }

    /** Whether two zones have the same offset now and at every change of either over the next ten years. */
    private static boolean keepTheSameTime(ZoneId one, ZoneId other, Instant now) {
        var oneRules = one.getRules();
        var otherRules = other.getRules();
        var until = now.atOffset(ZoneOffset.UTC).plusYears(10).toInstant();
        var at = now;
        while (at.isBefore(until)) {
            if (!oneRules.getOffset(at).equals(otherRules.getOffset(at))) {
                return false;
            }
            var next = Stream.of(oneRules.nextTransition(at), otherRules.nextTransition(at))
                    .filter(Objects::nonNull)
                    .map(ZoneOffsetTransition::getInstant)
                    .min(Comparator.naturalOrder());
            if (next.isEmpty()) {
                return true;
            }
            at = next.get();
        }
        return true;
    }
}
