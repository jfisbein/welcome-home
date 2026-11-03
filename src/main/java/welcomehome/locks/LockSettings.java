package welcomehome.locks;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;

/**
 * What an owner configures on a lock: its name, its time zone, whether the door is relocked some other way than
 * Auto Lock, for a smart lock the Opener at its building's entrance, and what its guests should know. Links store their start and end in UTC,
 * so changing the time zone changes how they are shown, not when they are valid.
 */
@Service
class LockSettings {

    static final int MAX_NAME_LENGTH = 100;
    static final int MAX_GUEST_INSTRUCTIONS_LENGTH = 1000;

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

    /**
     * @param name           a name already checked with {@link #normalizeName}
     * @param timeZone       a time zone already checked with {@link #isTimeZone}
     * @param entranceLockId an entrance among those {@link #entrances} offers, or null for none
     * @param guestInstructions instructions already checked with {@link #normalizeGuestInstructions}, or null for none
     * @return whether the owner has that lock
     */
    boolean update(UUID ownerId, UUID lockId, String name, String timeZone, boolean relockingHandledElsewhere,
                   @Nullable UUID entranceLockId, @Nullable String guestInstructions) {
        return ownerTransactions.execute(ownerId, () -> locks.updateSettings(ownerId, lockId, name, timeZone,
                relockingHandledElsewhere, entranceLockId, guestInstructions));
    }

    /**
     * The locks that may open a lock's building entrance: the owner's Openers, for a smart lock. An Opener has
     * none, as it is an entrance itself.
     *
     * @param ownersLocks all the owner's locks
     */
    static List<OwnerLock> entrances(OwnerLock lock, List<OwnerLock> ownersLocks) {
        if (lock.opener()) {
            return List.of();
        }
        return ownersLocks.stream().filter(OwnerLock::opener).toList();
    }

    /** @return the name without surrounding spaces, or empty if it is blank or too long */
    static Optional<String> normalizeName(String name) {
        var stripped = name.strip();
        return stripped.isEmpty() || stripped.length() > MAX_NAME_LENGTH ? Optional.empty() : Optional.of(stripped);
    }

    /**
     * The instructions without surrounding spaces and with a browser's line breaks as one character each, so that
     * their length is what the owner sees.
     *
     * @return them, or empty if they are too long; blank ones are an empty string, which means none
     */
    static Optional<String> normalizeGuestInstructions(String instructions) {
        var normalized = instructions.replace("\r\n", "\n").strip();
        return normalized.length() > MAX_GUEST_INSTRUCTIONS_LENGTH ? Optional.empty() : Optional.of(normalized);
    }

    static boolean isTimeZone(String timeZone) {
        return TIME_ZONES.contains(timeZone);
    }
}
