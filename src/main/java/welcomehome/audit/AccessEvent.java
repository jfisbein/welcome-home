package welcomehome.audit;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A guest's attempt to open a door with a valid link.
 *
 * @param ownerId   the link's owner
 * @param linkId    the link
 * @param lockId    the link's lock
 * @param result    the outcome
 * @param reason    a short code for why, such as {@code expired} or {@code nuki-error}
 * @param ip        the guest's IP address
 * @param userAgent the guest's browser, if it said
 */
public record AccessEvent(UUID ownerId, UUID linkId, UUID lockId, AccessResult result, String reason, String ip,
                          @Nullable String userAgent) {
}
