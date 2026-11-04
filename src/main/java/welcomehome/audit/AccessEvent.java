package welcomehome.audit;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A guest's attempt to open a door with a valid link.
 *
 * @param ownerId   the link's owner
 * @param linkId    the link
 * @param lockId    the link's lock
 * @param entranceLockId the Opener at the lock's building entrance, when the guest opened that rather than the
 *                  lock, or {@code null}
 * @param outcome   what the attempt came to
 * @param ip        the guest's IP address
 * @param country   the guest's country, as an ISO 3166 code or T1 for Tor, if known
 * @param distanceM for a door that checks where its guests are, how far from it the guest was, in metres, if
 *                  their browser said
 * @param accuracyM how far off that distance may be, in metres
 * @param locationConfirmed for a door that checks where its guests are, whether it opened only because the guest
 *                  confirmed being in front of it, while they seemed far or their position was unknown; null for a
 *                  door that does not check
 */
public record AccessEvent(UUID ownerId, UUID linkId, UUID lockId, @Nullable UUID entranceLockId,
                          AccessOutcome outcome, String ip, @Nullable String country, @Nullable Integer distanceM,
                          @Nullable Integer accuracyM, @Nullable Boolean locationConfirmed) {

    /**
     * An attempt at a door that does not check where its guests are.
     *
     * @param ownerId        the link's owner
     * @param linkId         the link
     * @param lockId         the link's lock
     * @param entranceLockId the Opener at the lock's building entrance, when the guest opened that, or {@code null}
     * @param outcome        what the attempt came to
     * @param ip             the guest's IP address
     * @param country        the guest's country, if known
     */
    public AccessEvent(UUID ownerId, UUID linkId, UUID lockId, @Nullable UUID entranceLockId,
                       AccessOutcome outcome, String ip, @Nullable String country) {
        this(ownerId, linkId, lockId, entranceLockId, outcome, ip, country, null, null, null);
    }
}
