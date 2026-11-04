package welcomehome.links;

import java.util.UUID;

/**
 * An owner changed one of their links. Published inside the change's transaction, in the owner's context, so a
 * listener may record it with the change; a listener after commit may still read the link, unless it was deleted.
 *
 * @param ownerId the link's owner
 * @param lockId  the link's lock
 * @param linkId  the link
 * @param change  what changed
 */
public record LinkChanged(UUID ownerId, UUID lockId, UUID linkId, Change change) {

    /** What an owner did to a link. */
    public enum Change {

        /** A new link. */
        CREATED,
        /** New label, dates or schedule, with the same URL. */
        EDITED,
        /** A new URL, every earlier one invalid. */
        NEW_URL,
        /** It opens nothing any more. */
        REVOKED,
        /** Gone, with its history, once it could no longer open. */
        DELETED;

        /**
         * Whether the link may open the door after this change where before it might not have: a new link, other
         * dates or schedule, or a new URL.
         *
         * @return whether the change lets the link open
         */
        public boolean letsItOpen() {
            return this == CREATED || this == EDITED || this == NEW_URL;
        }
    }
}
