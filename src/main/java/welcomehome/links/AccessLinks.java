package welcomehome.links;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;
import welcomehome.links.LinkDao.DeletedLink;
import welcomehome.links.LinkDao.EditableLink;
import welcomehome.links.LinkDao.RevokedLink;
import welcomehome.locks.OwnerLock;

/**
 * An owner's access links: a link opens one lock between two instants, at the days and hours of its schedule if it
 * has one, through a signed URL that is never stored.
 */
@Service
class AccessLinks {

    private final LinkDao links;
    private final LinkUrls urls;
    private final OwnerTransactions ownerTransactions;
    private final LinkSchedules schedules;

    AccessLinks(LinkDao links, LinkUrls urls, OwnerTransactions ownerTransactions, LinkSchedules schedules) {
        this.links = links;
        this.urls = urls;
        this.ownerTransactions = ownerTransactions;
        this.schedules = schedules;
    }

    /**
     * @param label      a label already checked with {@link LinkForm}
     * @param validFrom  when the link starts to open the door
     * @param validUntil when it stops, after {@code validFrom}
     * @param schedule   the days and hours at which it opens between them
     * @return the new link's URL, to give to the guest
     */
    URI create(UUID ownerId, UUID lockId, String label, Instant validFrom, Instant validUntil, LinkSchedule schedule) {
        var inserted = ownerTransactions.execute(ownerId, () -> {
            var link = links.insert(ownerId, lockId, label, validFrom, validUntil);
            links.replaceSchedule(ownerId, link.id(), schedule);
            return link;
        });
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
     * Deletes a link that no longer opens the door, as it expired or was revoked, with its schedule and its attempts in
     * the history, ahead of the daily purge. One that can still open is revoked first, so deleting never stops a guest.
     *
     * @return the deleted link's lock and label, or empty if the owner has no such link or it can still open
     */
    Optional<DeletedLink> deleteEnded(UUID ownerId, UUID linkId) {
        return ownerTransactions.execute(ownerId, () -> links.deleteEnded(ownerId, linkId));
    }

    /** @return whether the owner has that link, whatever its state */
    boolean exists(UUID ownerId, UUID linkId) {
        return ownerTransactions.execute(ownerId, () -> links.exists(ownerId, linkId));
    }

    /** @return the link, or empty if the owner has no such link or it is revoked */
    Optional<EditableLink> findEditable(UUID ownerId, UUID linkId) {
        return ownerTransactions.execute(ownerId, () -> links.findEditable(ownerId, linkId));
    }

    /**
     * Changes a link's label, dates and schedule. Its URL stays the same, since it depends only on the link's id and
     * token version.
     *
     * @param label a label already checked with {@link LinkForm}
     * @return whether the owner has that link and it is not revoked
     */
    boolean update(UUID ownerId, UUID linkId, String label, Instant validFrom, Instant validUntil,
                   LinkSchedule schedule) {
        return ownerTransactions.execute(ownerId, () -> {
            var updated = links.update(ownerId, linkId, label, validFrom, validUntil);
            if (updated) {
                links.replaceSchedule(ownerId, linkId, schedule);
            }
            return updated;
        });
    }

    /**
     * Gives a link a new URL, for when the old one leaked: incrementing its token version makes every earlier
     * URL invalid. Its dates, schedule, label and state stay the same.
     *
     * @return the link's lock, label and new URL, or empty if the owner has no such link or it is revoked
     */
    Optional<RegeneratedUrl> regenerate(UUID ownerId, UUID linkId) {
        return ownerTransactions.execute(ownerId, () -> links.incrementTokenVersion(ownerId, linkId))
                .map(link -> new RegeneratedUrl(link.lockId(), link.label(), urls.url(link.id(), link.tokenVersion())));
    }

    /** The lock's links, usable ones first, each group with the latest start first. */
    List<LinkView> list(UUID ownerId, OwnerLock lock, Locale locale) {
        var zone = ZoneId.of(lock.timeZone());
        var now = Instant.now();
        var stored = ownerTransactions.execute(ownerId, () -> links.findByLock(ownerId, lock.id()));
        var linkSchedules = ownerTransactions.execute(ownerId, () -> links.findSchedulesByLock(ownerId, lock.id()));
        return stored.stream()
                .map(link -> new LinkView(
                        link.id(),
                        link.label(),
                        link.validFrom().atZoneSameInstant(zone),
                        link.validUntil().atZoneSameInstant(zone),
                        schedules.describe(linkSchedules.getOrDefault(link.id(), LinkSchedule.ANY_TIME), locale),
                        LinkStatus.of(link.validFrom().toInstant(), link.validUntil().toInstant(),
                                link.revokedAt() == null ? null : link.revokedAt().toInstant(), now),
                        urls.url(link.id(), link.tokenVersion())))
                .sorted(Comparator.comparing((LinkView link) -> !link.status().isUsable()))
                .toList();
    }
}
