package welcomehome.locks;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;

/**
 * What an owner configures on a lock: its name, its time zone and whether the door is relocked some other
 * way than Auto Lock. Links store their start and end in UTC, so changing the time zone changes how they
 * are shown, not when they are valid.
 */
@Service
class LockSettings {

    static final int MAX_NAME_LENGTH = 100;

    /** The IANA region zones, such as Europe/Madrid, plus UTC: no offsets and no legacy aliases like CET. */
    static final List<String> TIME_ZONES;

    static {
        var region = Pattern.compile("(Africa|America|Antarctica|Asia|Atlantic|Australia|Europe|Indian|Pacific)/.+");
        TIME_ZONES = ZoneId.getAvailableZoneIds().stream()
                .filter(zone -> region.matcher(zone).matches() || zone.equals("UTC"))
                .sorted()
                .toList();
    }

    private final LockDao locks;
    private final OwnerTransactions ownerTransactions;

    LockSettings(LockDao locks, OwnerTransactions ownerTransactions) {
        this.locks = locks;
        this.ownerTransactions = ownerTransactions;
    }

    Optional<OwnerLock> find(UUID ownerId, UUID lockId) {
        return ownerTransactions.execute(ownerId, () -> locks.find(ownerId, lockId));
    }

    /**
     * @param name     a name already checked with {@link #normalizeName}
     * @param timeZone a time zone already checked with {@link #isTimeZone}
     * @return whether the owner has that lock
     */
    boolean update(UUID ownerId, UUID lockId, String name, String timeZone, boolean relockingHandledElsewhere) {
        return ownerTransactions.execute(ownerId,
                () -> locks.updateSettings(ownerId, lockId, name, timeZone, relockingHandledElsewhere));
    }

    /** @return the name without surrounding spaces, or empty if it is blank or too long */
    static Optional<String> normalizeName(String name) {
        var stripped = name.strip();
        return stripped.isEmpty() || stripped.length() > MAX_NAME_LENGTH ? Optional.empty() : Optional.of(stripped);
    }

    static boolean isTimeZone(String timeZone) {
        return TIME_ZONES.contains(timeZone);
    }
}
