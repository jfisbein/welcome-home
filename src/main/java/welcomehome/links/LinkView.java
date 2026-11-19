package welcomehome.links;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A link as its lock's links page shows it.
 *
 * @param id         the link's id
 * @param label      who the link is for
 * @param validFrom  when it starts, in the lock's time zone
 * @param validUntil when it ends, in the lock's time zone
 * @param schedule   the days and hours at which it opens, in the reader's language; none if at any time
 * @param status     its state now
 * @param url        its guest URL, computed for the current token version
 * @param hasPin     whether it asks the guest for a PIN
 * @param pinLocked  whether it has been given so many wrong PINs that it opens no more, until its owner edits it or
 *                   gives it a new URL
 * @param lastOpened when it last opened the door, in the lock's time zone, or {@code null} if it never has
 */
record LinkView(UUID id, String label, ZonedDateTime validFrom, ZonedDateTime validUntil, List<String> schedule,
                LinkStatus status, URI url, boolean hasPin, boolean pinLocked, @Nullable ZonedDateTime lastOpened) {
}
