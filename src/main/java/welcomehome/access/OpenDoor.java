package welcomehome.access;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import welcomehome.access.GuestLinks.VerifiedLink;
import welcomehome.audit.AccessEvent;
import welcomehome.audit.AccessEvents;
import welcomehome.audit.AccessOutcome;
import welcomehome.links.LinkStatus;
import welcomehome.locks.LocationCheck;
import welcomehome.locks.LockReachability;
import welcomehome.locks.OwnerLock;
import welcomehome.nuki.Coordinates;
import welcomehome.nuki.LockProvider;
import welcomehome.nuki.LockProviderException;
import welcomehome.nuki.LockProviderException.Reason;

/**
 * Opens a door for a guest. The checks run in this order, cheapest and least revealing first:
 * <ol>
 *     <li>rate limit per network: an IPv4 address, or the /64 an IPv6 address is in;</li>
 *     <li>the link exists and its MAC is valid;</li>
 *     <li>rate limit per link, whatever its state and wherever the attempts come from, so that the holder of a
 *     revoked link's URL cannot fill its owner's history: of the attempts it refuses, one per period is recorded;</li>
 *     <li>the link is not revoked nor paused, within its dates, and within its schedule if it has one;</li>
 *     <li>the link's PIN, if it has one: a link that has spent its wrong PINs refuses every attempt, for a while or,
 *     past their total, until its owner acts, and a wrong one counts against the link;</li>
 *     <li>the door is available: its lock is active and the owner's Nuki connection is OK; for the building
 *     entrance, the link's lock has one, an Opener, and it is active;</li>
 *     <li>for a door that checks where its guests are, the guest is in front of it, or confirms being so
 *     ({@link DoorDistance}).</li>
 * </ol>
 * What the checks read of the link, its lock and its owner's connection is read once, when the token is verified
 * ({@link GuestLinks#find}); only the PIN is checked apart, as a wrong one is counted. Only then is the door
 * unlatched, or the entrance's electric strike actuated, in the context of the owner that resolve_link returned,
 * which the verified MAC vouches for, and once per lock at a time: concurrent guests share one action and its
 * result.
 * The result waits for the lock's log to confirm the unlatch, also when Nuki did not answer
 * the unlatch, as it may have run it. Every attempt with a valid link is recorded in the owner's history; once the
 * lock may have been asked, nothing that fails afterwards, recording included, changes what the guest is told.
 */
@Service
class OpenDoor {

    private static final Logger log = LoggerFactory.getLogger(OpenDoor.class);

    private final RateLimiter rateLimiter;
    private final GuestLinks guestLinks;
    private final LockProvider lockProvider;
    private final LockMutex lockMutex;
    private final UnlatchConfirmation confirmation;
    private final AccessEvents accessEvents;
    private final LockReachability reachability;
    private final Clock clock;

    OpenDoor(RateLimiter rateLimiter, GuestLinks guestLinks, LockProvider lockProvider,
             LockMutex lockMutex, UnlatchConfirmation confirmation, AccessEvents accessEvents,
             LockReachability reachability, Clock clock) {
        this.clock = clock;
        this.rateLimiter = rateLimiter;
        this.guestLinks = guestLinks;
        this.lockProvider = lockProvider;
        this.lockMutex = lockMutex;
        this.confirmation = confirmation;
        this.accessEvents = accessEvents;
        this.reachability = reachability;
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
     * @param pin       what the guest typed when the link asked for a PIN, or null
     */
    Opening open(String token, Door door, String ip, @Nullable String country, @Nullable GuestPosition position,
                 boolean confirmed, @Nullable String pin) {
        if (!rateLimiter.tryAttemptFromIp(ip)) {
            return new Opening(AccessOutcome.TOO_MANY_ATTEMPTS, null);
        }
        var now = clock.instant();
        var found = guestLinks.find(token, now);
        if (found.isEmpty()) {
            return new Opening(AccessOutcome.INVALID, null);
        }
        var link = found.get();
        if (!rateLimiter.tryAttemptWithLink(link.linkId())) {
            var refused = Attempt.of(AccessOutcome.TOO_MANY_ATTEMPTS);
            if (rateLimiter.tellOfRefusal(link.linkId())) {
                record(link, door, refused, ip, country);
            }
            return new Opening(refused.outcome(), null);
        }
        var attempt = checkAndUnlatch(link, door, now, position, confirmed, pin);
        record(link, door, attempt, ip, country);
        var location = attempt.location();
        return new Opening(attempt.outcome(), location == null ? null : location.distanceM());
    }

    /** The token is verified: the attempt is recorded, with the entrance if that is the door asked for. */
    private void record(VerifiedLink link, Door door, Attempt attempt, String ip, @Nullable String country) {
        var entrance = door == Door.ENTRANCE ? link.entrance() : null;
        var location = attempt.location();
        try {
            accessEvents.record(new AccessEvent(link.ownerId(), link.linkId(), link.lockId(),
                    entrance == null ? null : entrance.id(), attempt.outcome(), ip, country,
                    location == null ? null : location.distanceM(), location == null ? null : location.accuracyM(),
                    location == null ? null : location.byConfirmation()));
        } catch (RuntimeException e) {
            // The door has been dealt with already: an error here would have the guest slide again, and open it
            // twice. The owner's history misses the attempt, which this line is all that tells.
            log.error("Could not record an attempt with link {} that came to {}", link.linkId(),
                    attempt.outcome().code(), e);
        }
    }

    private Attempt checkAndUnlatch(VerifiedLink link, Door door, Instant now, @Nullable GuestPosition position,
                                    boolean confirmed, @Nullable String pin) {
        if (link.status() == LinkStatus.REVOKED) {
            return Attempt.of(AccessOutcome.REVOKED);
        }
        if (link.status() == LinkStatus.PAUSED) {
            return Attempt.of(AccessOutcome.PAUSED);
        }
        if (link.status() == LinkStatus.PENDING) {
            return Attempt.of(AccessOutcome.NOT_YET_VALID);
        }
        if (link.status() == LinkStatus.EXPIRED) {
            return Attempt.of(AccessOutcome.EXPIRED);
        }
        if (!link.withinSchedule(now)) {
            return Attempt.of(AccessOutcome.OUTSIDE_SCHEDULE);
        }
        var pinOutcome = checkPin(link, pin);
        if (pinOutcome != null) {
            return Attempt.of(pinOutcome);
        }
        var entrance = door == Door.ENTRANCE;
        var lock = entrance ? link.entrance() : link.lock();
        if (lock == null || !(entrance ? link.entranceAvailable() : link.doorAvailable())) {
            return Attempt.of(AccessOutcome.UNAVAILABLE);
        }
        var location = checkLocation(lock, entrance ? link.entrancePlace() : lock.coordinates(), position, confirmed);
        if (location != null && location.verdict() != DoorDistance.Verdict.OPEN) {
            return refused(location);
        }
        var lockId = lock.id();
        var nukiSmartlockId = lock.nukiSmartlockId();
        return new Attempt(lockMutex.runOnce(lockId, () -> unlatchAndConfirm(link.ownerId(), lockId, nukiSmartlockId)),
                location);
    }

    /**
     * The PIN, if the link has one. Two limits stand against guessing it: a few wrong PINs in a while, kept in
     * memory, after which the link refuses before comparing anything, so that guessing gets nowhere even when it
     * hits; and a total of them, kept with the link, after which it is locked until its owner acts. The attempt is
     * taken before the PIN is compared and given back if it is right, so that PINs sent at once are not all compared
     * on the last attempt left.
     *
     * @return why the guest cannot go on, or null if the link has no PIN or the guest typed it
     */
    private @Nullable AccessOutcome checkPin(VerifiedLink link, @Nullable String typed) {
        if (!link.pinRequired()) {
            return null;
        }
        if (typed == null || typed.isBlank()) {
            return AccessOutcome.PIN_REQUIRED;
        }
        if (!rateLimiter.tryPin(link.linkId())) {
            return AccessOutcome.TOO_MANY_ATTEMPTS;
        }
        return switch (guestLinks.checkPin(link, typed)) {
            case MATCHES, NOT_SET -> {
                rateLimiter.pinWasRight(link.linkId());
                yield null;
            }
            case DOES_NOT_MATCH -> AccessOutcome.WRONG_PIN;
            case LOCKED -> AccessOutcome.PIN_LOCKED;
        };
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
        if (location.verdict() != DoorDistance.Verdict.REFUSE) {
            return new Attempt(AccessOutcome.CONFIRM_LOCATION, location);
        }
        // Refused without a distance: the door opens only for a guest it can place, and this one it could not.
        return new Attempt(location.distanceM() == null ? AccessOutcome.LOCATION_REQUIRED : AccessOutcome.TOO_FAR,
                location);
    }

    private AccessOutcome unlatchAndConfirm(UUID ownerId, UUID lockId, long nukiSmartlockId) {
        AccessOutcome outcome;
        try {
            outcome = unlatchAndAwait(ownerId, nukiSmartlockId);
        } catch (RuntimeException e) {
            // Whatever failed, the lock may have been asked: the guest is told to push the door before sliding again,
            // not sent an error that reads as nothing having happened, and the attempt is recorded.
            log.error("An opening of lock {} failed in a way no Nuki answer explains", lockId, e);
            outcome = AccessOutcome.NOT_CONFIRMED;
        }
        try {
            noteReachability(ownerId, lockId, outcome);
        } catch (RuntimeException e) {
            // The lock has been asked already: the guest must still learn what came of it.
            log.warn("Could not note whether Nuki reached lock {}", lockId, e);
        }
        return outcome;
    }

    /**
     * What the unlatch tells of Nuki's connection to the lock, which Nuki may wrongly report as offline
     * ({@link LockReachability}). A refusal in night mode tells nothing either way, nor does a failure that is
     * about the owner's connection to Nuki.
     */
    private void noteReachability(UUID ownerId, UUID lockId, AccessOutcome outcome) {
        switch (outcome) {
            case OPENED -> reachability.opened(ownerId, lockId);
            case NOT_CONFIRMED, NUKI_ERROR, LOCK_FAILED -> reachability.failedToOpen(ownerId, lockId);
            default -> { }
        }
    }

    private AccessOutcome unlatchAndAwait(UUID ownerId, long nukiSmartlockId) {
        // By the real clock, as the lock's log, which this is compared with, tells the real time.
        var requestedAt = Instant.now();
        try {
            lockProvider.unlatch(ownerId, nukiSmartlockId);
        } catch (LockProviderException e) {
            var failure = failure(e.reason());
            if (failure.isPresent()) {
                return failure.get();
            }
        }
        return confirmation.await(ownerId, nukiSmartlockId, requestedAt);
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
