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
 * @param userAgent the guest's browser, if it said
 */
public record AccessEvent(UUID ownerId, UUID linkId, UUID lockId, @Nullable UUID entranceLockId,
                          AccessOutcome outcome, String ip,
                          @Nullable String country, @Nullable String userAgent) {
}
