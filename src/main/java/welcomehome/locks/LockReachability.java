package welcomehome.locks;

import java.util.UUID;

import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;

/**
 * Whether to believe Nuki when it reports a lock offline. Nuki's Web API may report a lock offline for hours while
 * the lock is online and its actions run (docs/dev/nuki.md), so an opening that succeeds while Nuki reports the lock
 * offline disproves the report: the owner is not told of it, on their pages or by email, until an opening fails or
 * Nuki reports the lock online and then offline again.
 *
 * <p>Only what the app saw counts: a report starts when the app first reads it, so an opening that succeeded
 * before that does not disprove it, even if Nuki was already making it. And only the daily check of every lock
 * ({@link LockProblems}) starts and ends reports: a page shows what Nuki says now against the report as that check
 * left it and writes nothing, as when a report began must not depend on who looked at a page (review item COR-06).
 * So for up to a day a lock that went offline since the check is told as offline whatever it has opened, and one
 * that came back and went offline again keeps what was made of its earlier report.
 */
@Service
public class LockReachability {

    /** What is known of Nuki's connection to a lock. */
    public enum Connection {
        /** Nuki reports the lock online. */
        ONLINE,
        /** Nuki reports the lock offline, and nothing tells otherwise. */
        OFFLINE,
        /** Nuki reports the lock offline, yet it has opened it since. */
        OFFLINE_YET_OPENING
    }

    private final LockDao locks;
    private final OwnerTransactions ownerTransactions;

    LockReachability(LockDao locks, OwnerTransactions ownerTransactions) {
        this.locks = locks;
        this.ownerTransactions = ownerTransactions;
    }

    /**
     * Says what to make of what Nuki just reported of a lock's connection, for a page: nothing is written.
     *
     * @param online whether Nuki reports the lock online
     */
    Connection read(UUID ownerId, UUID lockId, boolean online) {
        if (online) {
            return Connection.ONLINE;
        }
        return ownerTransactions.execute(ownerId, () -> locks.findOfflineReport(ownerId, lockId))
                .filter(report -> report.reported() && report.disproved())
                .map(report -> Connection.OFFLINE_YET_OPENING).orElse(Connection.OFFLINE);
    }

    /**
     * Takes note of what Nuki just reported of a lock's connection, and says what to make of it. The daily check's
     * alone.
     *
     * @param online whether Nuki reports the lock online
     */
    Connection observe(UUID ownerId, UUID lockId, boolean online) {
        return ownerTransactions.execute(ownerId, () -> {
            var report = locks.findOfflineReport(ownerId, lockId).orElse(new LockDao.OfflineReport(false, false));
            if (online) {
                if (report.reported()) {
                    locks.endOfflineReport(ownerId, lockId);
                }
                return Connection.ONLINE;
            }
            if (!report.reported()) {
                locks.startOfflineReport(ownerId, lockId);
                return Connection.OFFLINE;
            }
            return report.disproved() ? Connection.OFFLINE_YET_OPENING : Connection.OFFLINE;
        });
    }

    /**
     * Nuki opened the lock: if it reports the lock offline, the report is not to be believed.
     *
     * @param ownerId the lock's owner
     * @param lockId  the lock, smart lock or Opener, that opened
     */
    public void opened(UUID ownerId, UUID lockId) {
        ownerTransactions.run(ownerId, () -> locks.disproveOfflineReport(ownerId, lockId));
    }

    /**
     * Nuki or the lock failed to open it, or nothing confirmed that it opened: a report that the lock is offline is
     * believed again.
     *
     * @param ownerId the lock's owner
     * @param lockId  the lock, smart lock or Opener, that did not open
     */
    public void failedToOpen(UUID ownerId, UUID lockId) {
        ownerTransactions.run(ownerId, () -> locks.believeOfflineReport(ownerId, lockId));
    }
}
