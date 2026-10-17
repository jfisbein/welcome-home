package welcomehome.links;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;
import welcomehome.locks.OwnerLock;

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

    /** The lock's links, usable ones first, each group with the latest start first. */
    List<LinkView> list(UUID ownerId, OwnerLock lock) {
        var zone = ZoneId.of(lock.timeZone());
        var now = Instant.now();
        return ownerTransactions.execute(ownerId, () -> links.findByLock(ownerId, lock.id())).stream()
                .map(link -> new LinkView(
                        link.id(),
                        link.label(),
                        link.validFrom().atZoneSameInstant(zone),
                        link.validUntil().atZoneSameInstant(zone),
                        LinkStatus.of(link.validFrom().toInstant(), link.validUntil().toInstant(),
                                link.revokedAt() == null ? null : link.revokedAt().toInstant(), now),
                        urls.url(link.id(), link.tokenVersion())))
                .sorted(Comparator.comparing((LinkView link) -> !link.status().isUsable()))
                .toList();
    }
}
