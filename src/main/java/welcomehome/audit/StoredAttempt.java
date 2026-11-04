package welcomehome.audit;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A guest's attempt as stored, with the names of its lock and link, from which every page draws its rows.
 *
 * @param id           the event's id
 * @param occurredAt   when it happened
 * @param timeZone     the time zone of the link's lock
 * @param lockId       the link's lock
 * @param lockName     its name
 * @param linkId       the link the guest used
 * @param linkLabel    its label
 * @param entranceName the name of the Opener at the lock's building entrance, if the guest opened that
 * @param result       whether the door opened, the app refused, or the lock or Nuki failed
 * @param reason       the short code that {@link AccessEvent} recorded
 * @param ip           the guest's IP address, if still kept
 * @param country      the guest's country code, if known
 * @param distanceM    for a door that checks where its guests are, how far from it the guest was, if still kept
 */
record StoredAttempt(UUID id, OffsetDateTime occurredAt, String timeZone, UUID lockId, String lockName, UUID linkId,
                     String linkLabel, @Nullable String entranceName, AccessResult result, @Nullable String reason,
                     @Nullable String ip, @Nullable String country, @Nullable Integer distanceM) {
}
