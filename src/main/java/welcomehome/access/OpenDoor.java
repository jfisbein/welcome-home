package welcomehome.access;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import welcomehome.access.GuestLinks.GuestLink;
import welcomehome.audit.AccessEvent;
import welcomehome.audit.AccessEvents;
import welcomehome.audit.AccessOutcome;
import welcomehome.links.LinkStatus;
import welcomehome.locks.LocationCheck;
import welcomehome.locks.Locks;
import welcomehome.locks.OwnerLock;
import welcomehome.nuki.Coordinates;
import welcomehome.nuki.LockProvider;
import welcomehome.nuki.LockProviderException;
import welcomehome.nuki.LockProviderException.Reason;
import welcomehome.nuki.PendingAction;

/**
 * Opens a door for a guest. The checks run in this order, cheapest and least revealing first:
 * <ol>
 *     <li>rate limit per IP address;</li>
 *     <li>the link exists, its MAC is valid and it is not revoked;</li>
 *     <li>rate limit per link;</li>
 *     <li>within the link's dates, and within its schedule if it has one;</li>
 *     <li>the door is available: its lock is active and the owner's Nuki connection is OK; for the building
 *     entrance, the link's lock has one, an Opener, and it is active;</li>
 *     <li>for a door that checks where its guests are, the guest is in front of it, or confirms being so
 *     ({@link DoorDistance}).</li>
 * </ol>
 * Only then is the door unlatched, or the entrance's electric strike actuated, in the context of the owner that
 * resolve_link returned, which the verified MAC vouches for, and once per lock at a time: concurrent guests share
 * one action and its result.
 * The result waits for Nuki to report the unlatch or the lock's log to confirm it, also when Nuki did not answer
 * the unlatch, as it may have run it. Every attempt with a valid link is recorded in the owner's history.
 */
@Service
class OpenDoor {

    private final RateLimiter rateLimiter;
    private final GuestLinks guestLinks;
    private final Locks locks;
    private final LockProvider lockProvider;
    private final LockMutex lockActions;
    private final UnlatchConfirmation confirmation;
    private final AccessEvents accessEvents;

    OpenDoor(RateLimiter rateLimiter, GuestLinks guestLinks, Locks locks, LockProvider lockProvider,
             LockMutex lockActions, UnlatchConfirmation confirmation, AccessEvents accessEvents) {
        this.rateLimiter = rateLimiter;
        this.guestLinks = guestLinks;
        this.locks = locks;
        this.lockProvider = lockProvider;
        this.lockActions = lockActions;
        this.confirmation = confirmation;
        this.accessEvents = accessEvents;
    }

    /**
     * What an attempt came to, for the guest page.
     *
     * @param distanceM how far from the door the guest was, in metres, for a door that checks it and a browser
     *                  that said; the page tells a guest asked to confirm, or refused
     */
    record Opening(AccessOutcome outcome, @Nullable Integer distanceM) {
    }

    /** What the checks came to, and the location check's verdict if the door has one. */
    private record Attempt(AccessOutcome outcome, DoorDistance.@Nullable Check location) {

        static Attempt of(AccessOutcome outcome) {
            return new Attempt(outcome, null);
        }
    }

    /**
     * @param position  where the guest's browser says they are, or null if it did not say
     * @param confirmed whether the guest confirmed being in front of the door, when asked to
     */
    Opening open(String token, Door door, String ip, @Nullable String country, @Nullable GuestPosition position,
                 boolean confirmed) {
        if (!rateLimiter.tryAttemptFromIp(ip)) {
            return new Opening(AccessOutcome.TOO_MANY_ATTEMPTS, null);
        }
        var found = guestLinks.find(token, Instant.now());
        if (found.isEmpty()) {
            return new Opening(AccessOutcome.INVALID, null);
        }
        var guestLink = found.get();
        var link = guestLink.link();
        // The token is verified: the entrance may be read, and the attempt is recorded with it.
        var entrance = door == Door.ENTRANCE ? guestLinks.entrance(link).orElse(null) : null;
        var attempt = checkAndUnlatch(guestLink, door, entrance, position, confirmed);
        var location = attempt.location();
        accessEvents.record(new AccessEvent(link.ownerId(), link.linkId(), link.lockId(),
                entrance == null ? null : entrance.id(), attempt.outcome(), ip, country,
                location == null ? null : location.distanceM(), location == null ? null : location.accuracyM(),
                location == null ? null : location.byConfirmation()));
        return new Opening(attempt.outcome(), location == null ? null : location.distanceM());
    }

    /** @param entrance the link's entrance, for {@link Door#ENTRANCE}, or null if it has none */
    private Attempt checkAndUnlatch(GuestLink guestLink, Door door, @Nullable OwnerLock entrance,
                                    @Nullable GuestPosition position, boolean confirmed) {
        var link = guestLink.link();
        if (guestLink.status() == LinkStatus.REVOKED) {
            return Attempt.of(AccessOutcome.REVOKED);
        }
        if (!rateLimiter.tryAttemptWithLink(link.linkId())) {
            return Attempt.of(AccessOutcome.TOO_MANY_ATTEMPTS);
        }
        if (guestLink.status() == LinkStatus.PENDING) {
            return Attempt.of(AccessOutcome.NOT_YET_VALID);
        }
        if (guestLink.status() == LinkStatus.EXPIRED) {
            return Attempt.of(AccessOutcome.EXPIRED);
        }
        if (!guestLinks.withinSchedule(link, Instant.now())) {
            return Attempt.of(AccessOutcome.OUTSIDE_SCHEDULE);
        }
        if (door == Door.ENTRANCE) {
            if (entrance == null || !guestLinks.entranceAvailable(link, entrance)) {
                return Attempt.of(AccessOutcome.UNAVAILABLE);
            }
            // An Opener without a position of its own is taken to be where its link's lock is: an entrance is rarely
            // more than some tens of metres from the door.
            var place = entrance.coordinates() != null ? entrance.coordinates()
                    : guestLinks.lock(link).map(OwnerLock::coordinates).orElse(null);
            var location = checkLocation(entrance, place, position, confirmed);
            if (location != null && location.verdict() != DoorDistance.Verdict.OPEN) {
                return refused(location);
            }
            var openerSmartlockId = entrance.nukiSmartlockId();
            return new Attempt(lockActions.runOnce(entrance.id(),
                    () -> unlatchAndConfirm(link.ownerId(), openerSmartlockId)), location);
        }
        if (!guestLinks.doorAvailable(link)) {
            return Attempt.of(AccessOutcome.UNAVAILABLE);
        }
        var lock = locks.find(link.ownerId(), link.lockId());
        if (lock.isEmpty()) {
            return Attempt.of(AccessOutcome.UNAVAILABLE);
        }
        var location = checkLocation(lock.get(), lock.get().coordinates(), position, confirmed);
        if (location != null && location.verdict() != DoorDistance.Verdict.OPEN) {
            return refused(location);
        }
        var nukiSmartlockId = lock.get().nukiSmartlockId();
        return new Attempt(lockActions.runOnce(link.lockId(), () -> unlatchAndConfirm(link.ownerId(), nukiSmartlockId)),
                location);
    }

    /**
     * Where the guest is, for a door that checks it.
     *
     * @param place where the door is, or null if nobody said
     * @return the check's verdict, or null if the door does not check, or has no place to check against
     */
    private static DoorDistance.@Nullable Check checkLocation(OwnerLock door, @Nullable Coordinates place,
                                                              @Nullable GuestPosition position, boolean confirmed) {
        if (door.locationCheck() == LocationCheck.OFF || place == null) {
            return null;
        }
        return DoorDistance.check(door.locationCheck(), door.locationRadiusM(), place, position, confirmed);
    }

    private static Attempt refused(DoorDistance.Check location) {
        return new Attempt(location.verdict() == DoorDistance.Verdict.REFUSE ? AccessOutcome.TOO_FAR
                : AccessOutcome.CONFIRM_LOCATION, location);
    }

    private AccessOutcome unlatchAndConfirm(UUID ownerId, long nukiSmartlockId) {
        var requestedAt = Instant.now();
        var unlatch = PendingAction.UNREPORTED;
        try {
            unlatch = lockProvider.unlatch(ownerId, nukiSmartlockId);
        } catch (LockProviderException e) {
            var failure = failure(e.reason());
            if (failure.isPresent()) {
                return failure.get();
            }
        }
        return confirmation.await(ownerId, nukiSmartlockId, requestedAt, unlatch);
    }

    /** What a failed unlatch comes to, or empty when only the lock's log can tell. */
    private static Optional<AccessOutcome> failure(Reason reason) {
        return switch (reason) {
            // Nuki may have run it: telling the guest to try again could open the door twice.
            case NO_ANSWER -> Optional.empty();
            case NUKI_ERROR -> Optional.of(AccessOutcome.NUKI_ERROR);
            case MISSING_PERMISSION -> Optional.of(AccessOutcome.MISSING_PERMISSION);
            case NOT_CONNECTED, ACCESS_DENIED, LOCK_NOT_FOUND -> Optional.of(AccessOutcome.UNAVAILABLE);
        };
    }
}
