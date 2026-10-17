package welcomehome.links;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;

/**
 * An owner's access links: a link opens one lock between two instants, through a signed URL that is never
 * stored.
 */
@Service
class AccessLinks {

    static final int MAX_LABEL_LENGTH = 100;

    private final LinkDao links;
    private final LinkUrls urls;
    private final OwnerTransactions ownerTransactions;

    AccessLinks(LinkDao links, LinkUrls urls, OwnerTransactions ownerTransactions) {
        this.links = links;
        this.urls = urls;
        this.ownerTransactions = ownerTransactions;
    }

    /**
     * @param label      a label already checked by the caller: 1 to {@link #MAX_LABEL_LENGTH} characters
     * @param validFrom  when the link starts to open the door
     * @param validUntil when it stops, after {@code validFrom}
     * @return the new link's URL, to give to the guest
     */
    URI create(UUID ownerId, UUID lockId, String label, Instant validFrom, Instant validUntil) {
        var inserted = ownerTransactions.execute(ownerId,
                () -> links.insert(ownerId, lockId, label, validFrom, validUntil));
        return urls.url(inserted.id(), inserted.tokenVersion());
    }
}
