package welcomehome.links;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;
import welcomehome.links.LinkDao.RevokedLink;
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

    record RegeneratedUrl(UUID lockId, String label, URI url) {
    }

    /**
     * Revokes a link at once: from now on it opens nothing, whatever its dates. Revocation cannot be undone.
     *
     * @return the revoked link's lock and label, or empty if the owner has no such link
     */
    Optional<RevokedLink> revoke(UUID ownerId, UUID linkId) {
        return ownerTransactions.execute(ownerId, () -> links.revoke(ownerId, linkId));
    }

    /**
     * Gives a link a new URL, for when the old one leaked: incrementing its token version makes every earlier
     * URL invalid. Its dates, label and state stay the same.
     *
     * @return the link's lock, label and new URL, or empty if the owner has no such link or it is revoked
     */
    Optional<RegeneratedUrl> regenerate(UUID ownerId, UUID linkId) {
        return ownerTransactions.execute(ownerId, () -> links.incrementTokenVersion(ownerId, linkId))
                .map(link -> new RegeneratedUrl(link.lockId(), link.label(), urls.url(link.id(), link.tokenVersion())));
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
