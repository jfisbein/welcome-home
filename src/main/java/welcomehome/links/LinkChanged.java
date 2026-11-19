package welcomehome.links;

import java.util.Set;
import java.util.UUID;

/**
 * An owner changed one of their links. Published inside the change's transaction, in the owner's context, so a
 * listener may record it with the change; a listener after commit may still read the link, unless it was deleted.
 *
 * @param ownerId the link's owner
 * @param lockId  the link's lock
 * @param linkId  the link
 * @param change  what changed
 * @param opensMore for an edit, in what ways the link may open where before it did not; none for an edit that only
 *                narrowed it or renamed it, and for the other changes
 */
public record LinkChanged(UUID ownerId, UUID lockId, UUID linkId, Change change, Set<OpensMore> opensMore) {

    /**
     * A change other than an edit that widened the link.
     *
     * @param ownerId the link's owner
     * @param lockId  the link's lock
     * @param linkId  the link
     * @param change  what changed
     */
    public LinkChanged(UUID ownerId, UUID lockId, UUID linkId, Change change) {
        this(ownerId, lockId, linkId, change, Set.of());
    }

    /** A way in which an edit lets a link open where before it did not. */
    public enum OpensMore {

        /** It starts earlier or ends later. */
        DATES,
        /** Its days and hours changed, other than a link that opened at any time getting some. */
        SCHEDULE,
        /** It asked for a PIN and no longer does. */
        PIN_REMOVED,
        /** It asks for another PIN. */
        PIN_CHANGED,
        /** It was locked by wrong PINs, and the edit let it open again. */
        UNLOCKED,
        /** It was paused, and its owner resumed it. */
        RESUMED
    }

    /**
     * Whether the link may open the door after this change where before it might not have: a new link, a new URL,
     * or an edit that widened it. Renaming a link, or narrowing it, is no news: an email for every edit would teach
     * its owner to ignore the one that tells of an intruder.
     *
     * @return whether the change lets the link open
     */
    public boolean letsItOpen() {
        return change == Change.CREATED || change == Change.NEW_URL || !opensMore.isEmpty();
    }

    /** What an owner did to a link. */
    public enum Change {

        /** A new link. */
        CREATED,
        /** New label, dates, schedule or PIN, with the same URL. */
        EDITED,
        /** A new URL, every earlier one invalid. */
        NEW_URL,
        /** It opens nothing any more. */
        REVOKED,
        /** Gone, with its history, once it could no longer open. */
        DELETED
    }
}
