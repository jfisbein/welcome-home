package welcomehome.links;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;
import welcomehome.links.LinkChanged.Change;
import welcomehome.links.LinkChanged.OpensMore;
import welcomehome.links.LinkDao.EndedLink;
import welcomehome.links.LinkDao.EditableLink;
import welcomehome.locks.OwnerLock;

/**
 * An owner's access links: a link opens one lock between two instants, at the days and hours of its schedule if it
 * has one, through a signed URL that is never stored. Every change publishes a {@link LinkChanged} in its
 * transaction.
 */
@Service
class AccessLinks {

    private final LinkDao links;
    private final LinkUrls urls;
    private final OwnerTransactions ownerTransactions;
    private final LinkSchedules schedules;
    private final LinkPins pins;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    AccessLinks(LinkDao links, LinkUrls urls, OwnerTransactions ownerTransactions, LinkSchedules schedules,
                LinkPins pins, ApplicationEventPublisher events, Clock clock) {
        this.clock = clock;
        this.links = links;
        this.urls = urls;
        this.ownerTransactions = ownerTransactions;
        this.schedules = schedules;
        this.pins = pins;
        this.events = events;
    }

    /**
     * @param label      a label already checked with {@link LinkForm}
     * @param validFrom  when the link starts to open the door
     * @param validUntil when it stops, after {@code validFrom}
     * @param schedule   the days and hours at which it opens between them
     * @param pin        the PIN the guest must type, checked with {@link LinkForm}, or null for none
     * @return the new link's URL, to give to the guest
     */
    URI create(UUID ownerId, UUID lockId, String label, Instant validFrom, Instant validUntil, LinkSchedule schedule,
               @Nullable String pin) {
        var inserted = ownerTransactions.execute(ownerId, () -> {
            var link = links.insert(ownerId, lockId, label, validFrom, validUntil);
            links.replaceSchedule(ownerId, link.id(), schedule);
            if (pin != null) {
                pins.set(ownerId, link.id(), pin);
            }
            events.publishEvent(new LinkChanged(ownerId, lockId, link.id(), Change.CREATED));
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
    Optional<EndedLink> revoke(UUID ownerId, UUID linkId) {
        return ownerTransactions.execute(ownerId, () -> {
            var revoked = links.revoke(ownerId, linkId);
            revoked.ifPresent(link -> events.publishEvent(new LinkChanged(ownerId, link.lockId(), linkId, Change.REVOKED)));
            return revoked;
        });
    }

    /**
     * Deletes a link that no longer opens the door, as it expired or was revoked, with its schedule and its attempts in
     * the history, ahead of the daily purge. One that can still open is revoked first, so deleting never stops a guest.
     *
     * @return the deleted link's lock and label, or empty if the owner has no such link or it can still open
     */
    Optional<EndedLink> deleteEnded(UUID ownerId, UUID linkId) {
        return ownerTransactions.execute(ownerId, () -> {
            var deleted = links.deleteEnded(ownerId, linkId);
            deleted.ifPresent(link -> events.publishEvent(new LinkChanged(ownerId, link.lockId(), linkId, Change.DELETED)));
            return deleted;
        });
    }

    /** @return whether the owner has that link, whatever its state */
    boolean exists(UUID ownerId, UUID linkId) {
        return ownerTransactions.execute(ownerId, () -> links.exists(ownerId, linkId));
    }

    /** @return the link's PIN for its owner to read, or empty if it has none */
    Optional<String> pinOf(UUID ownerId, UUID linkId) {
        return pins.of(ownerId, linkId);
    }

    /** @return the link, or empty if the owner has no such link or it is revoked */
    Optional<EditableLink> findEditable(UUID ownerId, UUID linkId) {
        return ownerTransactions.execute(ownerId, () -> links.findEditable(ownerId, linkId));
    }

    /**
     * Changes a link's label, dates, schedule and PIN. Its URL stays the same, since it depends only on the link's id and
     * token version. A link locked by wrong PINs opens again, with whatever PIN it is left with.
     *
     * @param label a label already checked with {@link LinkForm}
     * @param pin   the PIN, checked with {@link LinkForm}, or null to take it off
     * @return whether the owner has that link and it is not revoked
     */
    boolean update(UUID ownerId, UUID linkId, String label, Instant validFrom, Instant validUntil,
                   LinkSchedule schedule, @Nullable String pin) {
        return ownerTransactions.execute(ownerId, () -> {
            var before = links.findEditable(ownerId, linkId);
            if (before.isEmpty()) {
                return false;
            }
            var opensMore = opensMore(ownerId, before.get(), validFrom, validUntil, schedule, pin);
            var lockId = links.update(ownerId, linkId, label, validFrom, validUntil);
            lockId.ifPresent(lock -> {
                links.replaceSchedule(ownerId, linkId, schedule);
                pins.set(ownerId, linkId, pin);
                pins.unlock(ownerId, linkId);
                events.publishEvent(new LinkChanged(ownerId, lock, linkId, Change.EDITED, opensMore));
            });
            return lockId.isPresent();
        });
    }

    /**
     * In what ways an edit lets a link open where it did not, read before the edit is stored. A schedule that
     * changed counts unless the link opened at any time before, as telling a wider schedule from a narrower one is
     * not worth getting wrong.
     */
    private Set<OpensMore> opensMore(UUID ownerId, EditableLink before, Instant validFrom, Instant validUntil,
                                     LinkSchedule schedule, @Nullable String pin) {
        var opensMore = EnumSet.noneOf(OpensMore.class);
        if (validFrom.isBefore(before.validFrom().toInstant()) || validUntil.isAfter(before.validUntil().toInstant())) {
            opensMore.add(OpensMore.DATES);
        }
        var scheduleBefore = links.findSchedule(ownerId, before.id());
        if (!scheduleBefore.equals(schedule) && !scheduleBefore.equals(LinkSchedule.ANY_TIME)) {
            opensMore.add(OpensMore.SCHEDULE);
        }
        var pinBefore = pins.of(ownerId, before.id());
        if (pinBefore.isPresent() && pin == null) {
            opensMore.add(OpensMore.PIN_REMOVED);
        } else if (pinBefore.isPresent() && !pinBefore.get().equals(pin)) {
            opensMore.add(OpensMore.PIN_CHANGED);
        }
        if (pinBefore.isPresent() && pins.isLocked(links.findPinFailures(ownerId, before.id()))) {
            opensMore.add(OpensMore.UNLOCKED);
        }
        return opensMore;
    }

    /**
     * Gives a link a new URL, for when the old one leaked: incrementing its token version makes every earlier
     * URL invalid. Its dates, schedule, label and state stay the same, and one locked by wrong PINs opens again.
     *
     * @return the link's lock, label and new URL, or empty if the owner has no such link or it is revoked
     */
    Optional<RegeneratedUrl> regenerate(UUID ownerId, UUID linkId) {
        return ownerTransactions.execute(ownerId, () -> {
                    var regenerated = links.incrementTokenVersion(ownerId, linkId);
                    regenerated.ifPresent(link -> {
                        // Whoever was guessing its PIN held the URL that has just stopped working.
                        pins.unlock(ownerId, linkId);
                        events.publishEvent(new LinkChanged(ownerId, link.lockId(), linkId, Change.NEW_URL));
                    });
                    return regenerated;
                })
                .map(link -> new RegeneratedUrl(link.lockId(), link.label(), urls.url(link.id(), link.tokenVersion())));
    }

    /** The lock's links, usable ones first, each group with the latest start first. */
    List<LinkView> list(UUID ownerId, OwnerLock lock, Locale locale) {
        var zone = ZoneId.of(lock.timeZone());
        var now = clock.instant();
        var stored = ownerTransactions.execute(ownerId, () -> links.findByLock(ownerId, lock.id()));
        var linkSchedules = ownerTransactions.execute(ownerId, () -> links.findSchedulesByLock(ownerId, lock.id()));
        return stored.stream()
                .map(link -> new LinkView(
                        link.id(),
                        link.label(),
                        link.validFrom().atZoneSameInstant(zone),
                        link.validUntil().atZoneSameInstant(zone),
                        schedules.describe(linkSchedules.getOrDefault(link.id(), LinkSchedule.ANY_TIME), locale),
                        LinkStatus.of(link.validFrom(), link.validUntil(), link.revokedAt(), now),
                        urls.url(link.id(), link.tokenVersion()),
                        link.hasPin(),
                        link.hasPin() && pins.isLocked(link.pinFailures())))
                .sorted(Comparator.comparing((LinkView link) -> !link.status().isUsable()))
                .toList();
    }
}
