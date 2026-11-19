package welcomehome.access;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import welcomehome.crypto.LinkTokenSigner;
import welcomehome.db.OwnerTransactions;
import welcomehome.links.LinkPins;
import welcomehome.links.LinkSchedule;
import welcomehome.links.LinkSchedules;
import welcomehome.links.LinkStatus;
import welcomehome.links.OpenLinks;
import welcomehome.locks.Locks;
import welcomehome.locks.OwnerLock;
import welcomehome.nuki.Coordinates;
import welcomehome.nuki.LockConnection;
import welcomehome.nuki.LockProvider;

/**
 * Turns the token of a guest's URL into a link the app can trust: it resolves the link and accepts it only
 * if the token's MAC is valid for the link's current token version. Only then is anything read in the owner's
 * context, all of it at once: {@link VerifiedLink} is what the guest's page and an opening work on, and nothing but
 * {@link #find} makes one.
 */
@Service
class GuestLinks {

    private final LinkTokenSigner signer;
    private final ResolvedLinkDao resolvedLinks;
    private final LockProvider lockProvider;
    private final Locks locks;
    private final LinkSchedules schedules;
    private final LinkPins pins;
    private final OpenLinks openLinks;
    private final OwnerTransactions ownerTransactions;

    GuestLinks(LinkTokenSigner signer, ResolvedLinkDao resolvedLinks, LockProvider lockProvider, Locks locks,
               LinkSchedules schedules, LinkPins pins, OpenLinks openLinks, OwnerTransactions ownerTransactions) {
        this.openLinks = openLinks;
        this.signer = signer;
        this.resolvedLinks = resolvedLinks;
        this.lockProvider = lockProvider;
        this.locks = locks;
        this.schedules = schedules;
        this.pins = pins;
        this.ownerTransactions = ownerTransactions;
    }

    /**
     * @return the link with its state at {@code now} and what its owner keeps about it, or empty if the token is
     * malformed, its link does not exist, or its MAC is invalid, for example after the link was regenerated. The
     * three cases look the same, so that a guest cannot tell which links exist.
     */
    Optional<VerifiedLink> find(String token, Instant now) {
        return signer.parseLinkId(token)
                .flatMap(resolvedLinks::resolve)
                .filter(link -> signer.verify(token, link.tokenVersion()))
                .map(link -> read(link, now));
    }

    /** In one transaction of the owner's, which the verified MAC vouches for: the same rows, read once. */
    private VerifiedLink read(ResolvedLink link, Instant now) {
        var ownerId = link.ownerId();
        return ownerTransactions.execute(ownerId, () -> {
            var lock = locks.find(ownerId, link.lockId()).orElse(null);
            var entranceId = lock == null ? null : lock.entranceLockId();
            var entrance = entranceId == null ? null : locks.find(ownerId, entranceId).orElse(null);
            // resolve_link does not say whether a revoked link was paused: asked only of one that reads as revoked.
            var paused = link.revokedAt() != null && openLinks.isPaused(ownerId, link.linkId());
            var status = LinkStatus.of(link.validFrom(), link.validUntil(), link.revokedAt(), paused, now);
            return new VerifiedLink(link, status, schedules.of(ownerId, link.linkId()),
                    pins.isSet(ownerId, link.linkId()), lock, entrance,
                    lockProvider.findConnection(ownerId).filter(LockConnection::works).isPresent());
        });
    }

    /**
     * Compares what a guest typed with the link's PIN, counting a wrong one against the link: the one thing that is
     * not read with the rest, as it is decided, and written, under a lock on the link's row.
     */
    LinkPins.Check checkPin(VerifiedLink link, @Nullable String typed) {
        return pins.check(link.ownerId(), link.linkId(), typed);
    }

    /**
     * A link whose token was verified, with what a guest's page or an opening needs of it, as it was when read.
     */
    static final class VerifiedLink {

        private final ResolvedLink link;
        private final LinkStatus status;
        private final LinkSchedule schedule;
        private final boolean pinRequired;
        private final @Nullable OwnerLock lock;
        private final @Nullable OwnerLock entrance;
        private final boolean connected;

        private VerifiedLink(ResolvedLink link, LinkStatus status, LinkSchedule schedule, boolean pinRequired,
                             @Nullable OwnerLock lock, @Nullable OwnerLock entrance, boolean connected) {
            this.link = link;
            this.status = status;
            this.schedule = schedule;
            this.pinRequired = pinRequired;
            this.lock = lock;
            this.entrance = entrance;
            this.connected = connected;
        }

        UUID ownerId() {
            return link.ownerId();
        }

        UUID linkId() {
            return link.linkId();
        }

        UUID lockId() {
            return link.lockId();
        }

        String lockName() {
            return link.lockName();
        }

        ZoneId zone() {
            return ZoneId.of(link.lockTimeZone());
        }

        String timeZone() {
            return link.lockTimeZone();
        }

        Instant validFrom() {
            return link.validFrom().toInstant();
        }

        Instant validUntil() {
            return link.validUntil().toInstant();
        }

        /** The link's state by its dates, at the instant it was read. */
        LinkStatus status() {
            return status;
        }

        /** The days and hours at which it opens, which allow any time if it has none. */
        LinkSchedule schedule() {
            return schedule;
        }

        /** Whether its schedule lets it open at an instant, in its lock's time zone. */
        boolean withinSchedule(Instant at) {
            return schedule.allows(at, zone());
        }

        /** Whether it asks its guest for a PIN. */
        boolean pinRequired() {
            return pinRequired;
        }

        /** Its lock as its owner keeps it, or null if it is gone. */
        @Nullable OwnerLock lock() {
            return lock;
        }

        /** The Opener at the building entrance of its lock, or null if the lock has none. */
        @Nullable OwnerLock entrance() {
            return entrance;
        }

        /**
         * Whether its door can be opened at all, whatever its dates: its lock is active and its owner's Nuki
         * connection is OK.
         */
        boolean doorAvailable() {
            return lock != null && lock.active() && connected;
        }

        /** Whether its entrance can be opened at all, as {@link #doorAvailable} says of its lock. */
        boolean entranceAvailable() {
            return entrance != null && entrance.active() && connected;
        }

        /**
         * Where its entrance is, for the check of where a guest is: an Opener without a position of its own is taken
         * to be where its link's lock is, as an entrance is rarely more than some tens of metres from the door.
         */
        @Nullable Coordinates entrancePlace() {
            if (entrance == null) {
                return null;
            }
            return entrance.coordinates() != null || lock == null ? entrance.coordinates() : lock.coordinates();
        }
    }
}
