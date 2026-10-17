package welcomehome.access;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import welcomehome.access.GuestLinks.GuestLink;
import welcomehome.audit.AccessEvent;
import welcomehome.audit.AccessEvents;
import welcomehome.audit.AccessResult;
import welcomehome.links.LinkStatus;
import welcomehome.locks.Locks;
import welcomehome.nuki.ConnectionStatus;
import welcomehome.nuki.LockProvider;
import welcomehome.nuki.LockProviderException;
import welcomehome.nuki.NukiConnections;

/**
 * Opens a door for a guest. The checks run in this order, cheapest and least revealing first:
 * <ol>
 *     <li>rate limit per IP address;</li>
 *     <li>the link exists, its MAC is valid and it is not revoked;</li>
 *     <li>rate limit per link;</li>
 *     <li>within the link's dates;</li>
 *     <li>the lock is active;</li>
 *     <li>the owner's Nuki connection is OK.</li>
 * </ol>
 * Only then is the door unlatched, in the context of the owner that resolve_link returned, which the
 * verified MAC vouches for, and once per lock at a time: concurrent guests share one action and its result.
 * Every attempt with a valid link is recorded in the owner's history.
 */
@Service
class OpenDoor {

    private final RateLimiter rateLimiter;
    private final GuestLinks guestLinks;
    private final Locks locks;
    private final NukiConnections nukiConnections;
    private final LockProvider lockProvider;
    private final LockActions lockActions;
    private final AccessEvents accessEvents;

    OpenDoor(RateLimiter rateLimiter, GuestLinks guestLinks, Locks locks, NukiConnections nukiConnections,
             LockProvider lockProvider, LockActions lockActions, AccessEvents accessEvents) {
        this.rateLimiter = rateLimiter;
        this.guestLinks = guestLinks;
        this.locks = locks;
        this.nukiConnections = nukiConnections;
        this.lockProvider = lockProvider;
        this.lockActions = lockActions;
        this.accessEvents = accessEvents;
    }

    OpenResult open(String token, String ip, @Nullable String userAgent) {
        if (!rateLimiter.tryAttemptFromIp(ip)) {
            return OpenResult.TOO_MANY_ATTEMPTS;
        }
        var found = guestLinks.find(token, Instant.now());
        if (found.isEmpty()) {
            return OpenResult.INVALID;
        }
        var guestLink = found.get();
        var result = checkAndUnlatch(guestLink);
        var accessResult = switch (result) {
            case REQUESTED -> AccessResult.OK;
            case NUKI_ERROR -> AccessResult.ERROR;
            default -> AccessResult.DENIED;
        };
        var link = guestLink.link();
        accessEvents.record(new AccessEvent(link.ownerId(), link.linkId(), link.lockId(), accessResult, result.code, ip,
                userAgent));
        return result;
    }

    private OpenResult checkAndUnlatch(GuestLink guestLink) {
        var link = guestLink.link();
        if (guestLink.status() == LinkStatus.REVOKED) {
            return OpenResult.REVOKED;
        }
        if (!rateLimiter.tryAttemptWithLink(link.linkId())) {
            return OpenResult.TOO_MANY_ATTEMPTS;
        }
        if (guestLink.status() == LinkStatus.PENDING) {
            return OpenResult.NOT_YET_VALID;
        }
        if (guestLink.status() == LinkStatus.EXPIRED) {
            return OpenResult.EXPIRED;
        }
        if (!link.lockActive()) {
            return OpenResult.UNAVAILABLE;
        }
        var connected = nukiConnections.find(link.ownerId())
                .filter(connection -> connection.status() == ConnectionStatus.OK)
                .isPresent();
        var lock = locks.find(link.ownerId(), link.lockId());
        if (!connected || lock.isEmpty()) {
            return OpenResult.UNAVAILABLE;
        }
        var nukiSmartlockId = lock.get().nukiSmartlockId();
        return lockActions.runOnce(link.lockId(), () -> unlatch(link.ownerId(), nukiSmartlockId));
    }

    private OpenResult unlatch(UUID ownerId, long nukiSmartlockId) {
        try {
            lockProvider.unlatch(ownerId, nukiSmartlockId);
            return OpenResult.REQUESTED;
        } catch (LockProviderException e) {
            return e.reason() == LockProviderException.Reason.UNAVAILABLE ? OpenResult.NUKI_ERROR : OpenResult.UNAVAILABLE;
        }
    }
}
