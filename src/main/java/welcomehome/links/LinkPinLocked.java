package welcomehome.links;

import java.util.UUID;

/**
 * A link has been given so many wrong PINs that it refuses every attempt from now on, until its owner edits it or
 * gives it a new URL: someone who holds its URL may be guessing. Published inside the transaction that counted the
 * last wrong PIN, in the owner's context.
 *
 * @param ownerId  the link's owner
 * @param lockId   the link's lock
 * @param linkId   the link
 * @param failures how many wrong PINs it was given
 */
public record LinkPinLocked(UUID ownerId, UUID lockId, UUID linkId, int failures) {
}
