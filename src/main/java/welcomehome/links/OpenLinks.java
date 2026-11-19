package welcomehome.links;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;

/**
 * Which of an owner's links can open: how many can open each of their locks right now, for the home page, as a
 * link made for a guest long gone still opens the door and its owner may not remember it; and whether one that
 * cannot was paused.
 */
@Service
public class OpenLinks {

    private final LinkDao links;
    private final OwnerTransactions ownerTransactions;

    OpenLinks(LinkDao links, OwnerTransactions ownerTransactions) {
        this.links = links;
        this.ownerTransactions = ownerTransactions;
    }

    /**
     * Counts the owner's links within their dates and not revoked, whatever their schedule, which opens and closes
     * every day.
     *
     * @param ownerId the owner
     * @return how many such links each lock has, by the lock's id; a lock with none is not in it
     */
    public Map<UUID, Integer> countByLock(UUID ownerId) {
        return ownerTransactions.execute(ownerId, () -> links.countOpenByLock(ownerId));
    }

    /**
     * Tells whether a link that reads as revoked was paused, which its owner can end. In the owner's transaction.
     *
     * @param ownerId the link's owner
     * @param linkId  the link
     * @return whether the link is paused
     */
    public boolean isPaused(UUID ownerId, UUID linkId) {
        return links.isPaused(ownerId, linkId);
    }
}
