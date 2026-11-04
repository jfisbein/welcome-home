package welcomehome.locks;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;
import welcomehome.nuki.Coordinates;

/**
 * What an owner configures on a lock: its name, its time zone, whether the door is relocked some other way than
 * Auto Lock, for a smart lock the Opener at its building's entrance, what its guests should know, and where it is. Links store their start and end in UTC,
 * so changing the time zone changes how they are shown, not when they are valid.
 */
@Service
class LockSettings {

    static final int MAX_NAME_LENGTH = 100;
    static final int MAX_GUEST_INSTRUCTIONS_LENGTH = 1000;

    /** A latitude and a longitude in degrees, separated by a comma or spaces, as Google Maps copies a place. */
    private static final Pattern COORDINATES =
            Pattern.compile("(-?\\d+(?:\\.\\d+)?)(?:\\s*,\\s*|\\s+)(-?\\d+(?:\\.\\d+)?)");

    /** Six decimals are some 10 cm, more than any phone knows. */
    private static final double DECIMALS = 1e6;

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
     * @param coordinates    where the lock is, or null for nowhere known: the next sync then takes Nuki's, if it
     *                       has any
     * @return whether the owner has that lock
     */
    boolean update(UUID ownerId, UUID lockId, String name, String timeZone, boolean relockingHandledElsewhere,
                   @Nullable UUID entranceLockId, @Nullable String guestInstructions,
                   @Nullable Coordinates coordinates) {
        return ownerTransactions.execute(ownerId, () -> locks.updateSettings(ownerId, lockId, name, timeZone,
                relockingHandledElsewhere, entranceLockId, guestInstructions, coordinates));
    }

    /**
     * A lock's position as the owner typed it.
     *
     * @param valid       whether it was nothing or coordinates
     * @param coordinates the coordinates, or null for nothing or for something else
     */
    record TypedCoordinates(boolean valid, @Nullable Coordinates coordinates) {
    }

    /**
     * Reads a lock's position: a latitude and a longitude with a decimal point, separated by a comma or spaces,
     * such as {@code 40.416775, -3.703790}, as Google Maps copies a place, rounded to six decimals. Blank is none.
     */
    static TypedCoordinates parseCoordinates(String text) {
        var stripped = text.strip();
        if (stripped.isEmpty()) {
            return new TypedCoordinates(true, null);
        }
        var matcher = COORDINATES.matcher(stripped);
        if (!matcher.matches()) {
            return new TypedCoordinates(false, null);
        }
        try {
            return new TypedCoordinates(true, new Coordinates(rounded(matcher.group(1)), rounded(matcher.group(2))));
        } catch (IllegalArgumentException e) {
            return new TypedCoordinates(false, null);
        }
    }

    /** As the owner reads and types them: the latitude, a comma and the longitude, with six decimals. */
    static String format(@Nullable Coordinates coordinates) {
        return coordinates == null ? ""
                : String.format(Locale.ROOT, "%.6f, %.6f", coordinates.latitude(), coordinates.longitude());
    }

    /** Where the lock is on OpenStreetMap, with a marker, for the owner to check it. */
    static String mapUrl(Coordinates coordinates) {
        var latitude = String.format(Locale.ROOT, "%.6f", coordinates.latitude());
        var longitude = String.format(Locale.ROOT, "%.6f", coordinates.longitude());
        return "https://www.openstreetmap.org/?mlat=" + latitude + "&mlon=" + longitude
                + "#map=18/" + latitude + "/" + longitude;
    }

    private static double rounded(String degrees) {
        return Math.round(Double.parseDouble(degrees) * DECIMALS) / DECIMALS;
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
