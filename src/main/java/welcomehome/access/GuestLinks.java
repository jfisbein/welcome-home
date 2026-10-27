package welcomehome.access;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import welcomehome.crypto.LinkTokenSigner;
import welcomehome.links.LinkStatus;
import welcomehome.locks.Locks;
import welcomehome.locks.OwnerLock;
import welcomehome.nuki.ConnectionStatus;
import welcomehome.nuki.NukiConnections;

/**
 * Turns the token of a guest's URL into a link the app can trust: it resolves the link and accepts it only
 * if the token's MAC is valid for the link's current token version.
 */
@Service
class GuestLinks {

    private final LinkTokenSigner signer;
    private final ResolvedLinkDao resolvedLinks;
    private final NukiConnections nukiConnections;
    private final Locks locks;

    GuestLinks(LinkTokenSigner signer, ResolvedLinkDao resolvedLinks, NukiConnections nukiConnections, Locks locks) {
        this.signer = signer;
        this.resolvedLinks = resolvedLinks;
        this.nukiConnections = nukiConnections;
        this.locks = locks;
    }

    /**
     * @return the link with its state at {@code now}, or empty if the token is malformed, its link does not
     * exist, or its MAC is invalid, for example after the link was regenerated. The three cases look the
     * same, so that a guest cannot tell which links exist.
     */
    Optional<GuestLink> find(String token, Instant now) {
        return signer.parseLinkId(token)
                .flatMap(resolvedLinks::resolve)
                .filter(link -> signer.verify(token, link.tokenVersion()))
                .map(link -> new GuestLink(link, LinkStatus.of(link.validFrom().toInstant(),
                        link.validUntil().toInstant(), link.revokedAt() == null ? null : link.revokedAt().toInstant(),
                        now)));
    }

    /**
     * Tells whether a link's door can be opened at all, whatever the link's dates: its lock is active and its
     * owner's Nuki connection is OK. It reads only the database, so the guest page can ask too.
     */
    boolean doorAvailable(ResolvedLink link) {
        return link.lockActive() && connected(link);
    }

    /**
     * Tells whether a link's entrance can be opened at all, as {@link #doorAvailable} does for its lock.
     *
     * @param entrance the link's entrance, as {@link #entrance} found it
     */
    boolean entranceAvailable(ResolvedLink link, OwnerLock entrance) {
        return entrance.active() && connected(link);
    }

    /**
     * The link's lock as its owner sees it, read in the owner's context: only for a link whose token was verified.
     *
     * @return the lock, or empty if it is gone
     */
    Optional<OwnerLock> lock(ResolvedLink link) {
        return locks.find(link.ownerId(), link.lockId());
    }

    /**
     * The Opener at the building entrance of a link's lock, read in the owner's context: only for a link whose token
     * was verified.
     *
     * @return the Opener, or empty if the lock has no entrance
     */
    Optional<OwnerLock> entrance(ResolvedLink link) {
        return lock(link).flatMap(lock -> {
            var entranceId = lock.entranceLockId();
            return entranceId == null ? Optional.empty() : locks.find(link.ownerId(), entranceId);
        });
    }

    private boolean connected(ResolvedLink link) {
        return nukiConnections.find(link.ownerId())
                .filter(connection -> connection.status() == ConnectionStatus.OK)
                .isPresent();
    }

    /**
     * A link whose token was verified.
     *
     * @param link   the link and its lock
     * @param status the link's state
     */
    record GuestLink(ResolvedLink link, LinkStatus status) {
    }
}
