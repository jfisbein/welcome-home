package welcomehome.links;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * A link as its lock's links page shows it.
 *
 * @param id         the link's id
 * @param label      who the link is for
 * @param validFrom  when it starts, in the lock's time zone
 * @param validUntil when it ends, in the lock's time zone
 * @param status     its state now
 * @param url        its guest URL, computed for the current token version
 */
record LinkView(UUID id, String label, ZonedDateTime validFrom, ZonedDateTime validUntil, LinkStatus status, URI url) {
}
