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
import welcomehome.locks.Locks;
import welcomehome.nuki.LockProvider;
import welcomehome.nuki.LockProviderException;
import welcomehome.nuki.LockProviderException.Reason;

/**
 * Opens a door for a guest. The checks run in this order, cheapest and least revealing first:
 * <ol>
 *     <li>rate limit per IP address;</li>
 *     <li>the link exists, its MAC is valid and it is not revoked;</li>
 *     <li>rate limit per link;</li>
 *     <li>within the link's dates;</li>
 *     <li>the door is available: its lock is active and the owner's Nuki connection is OK.</li>
 * </ol>
 * Only then is the door unlatched, in the context of the owner that resolve_link returned, which the
 * verified MAC vouches for, and once per lock at a time: concurrent guests share one action and its result.
 * The result waits for the lock's log to confirm the unlatch, also when Nuki did not answer the unlatch, as it
 * may have run it. Every attempt with a valid link is recorded in the owner's history.
 */
@Service
class OpenDoor {

    private final RateLimiter rateLimiter;
    private final GuestLinks guestLinks;
    private final Locks locks;
    private final LockProvider lockProvider;
    private final LockActions lockActions;
    private final UnlatchConfirmation confirmation;
    private final AccessEvents accessEvents;

    OpenDoor(RateLimiter rateLimiter, GuestLinks guestLinks, Locks locks, LockProvider lockProvider,
             LockActions lockActions, UnlatchConfirmation confirmation, AccessEvents accessEvents) {
        this.rateLimiter = rateLimiter;
        this.guestLinks = guestLinks;
        this.locks = locks;
        this.lockProvider = lockProvider;
        this.lockActions = lockActions;
        this.confirmation = confirmation;
        this.accessEvents = accessEvents;
    }

    AccessOutcome open(String token, String ip, @Nullable String userAgent) {
        if (!rateLimiter.tryAttemptFromIp(ip)) {
            return AccessOutcome.TOO_MANY_ATTEMPTS;
        }
        var found = guestLinks.find(token, Instant.now());
        if (found.isEmpty()) {
            return AccessOutcome.INVALID;
        }
        var guestLink = found.get();
        var result = checkAndUnlatch(guestLink);
        var link = guestLink.link();
        accessEvents.record(new AccessEvent(link.ownerId(), link.linkId(), link.lockId(), result, ip, userAgent));
        return result;
    }

    private AccessOutcome checkAndUnlatch(GuestLink guestLink) {
        var link = guestLink.link();
        if (guestLink.status() == LinkStatus.REVOKED) {
            return AccessOutcome.REVOKED;
        }
        if (!rateLimiter.tryAttemptWithLink(link.linkId())) {
            return AccessOutcome.TOO_MANY_ATTEMPTS;
        }
        if (guestLink.status() == LinkStatus.PENDING) {
            return AccessOutcome.NOT_YET_VALID;
        }
        if (guestLink.status() == LinkStatus.EXPIRED) {
            return AccessOutcome.EXPIRED;
        }
        if (!guestLinks.doorAvailable(link)) {
            return AccessOutcome.UNAVAILABLE;
        }
        var lock = locks.find(link.ownerId(), link.lockId());
        if (lock.isEmpty()) {
            return AccessOutcome.UNAVAILABLE;
        }
        var nukiSmartlockId = lock.get().nukiSmartlockId();
        return lockActions.runOnce(link.lockId(), () -> unlatchAndConfirm(link.ownerId(), nukiSmartlockId));
    }

    private AccessOutcome unlatchAndConfirm(UUID ownerId, long nukiSmartlockId) {
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
