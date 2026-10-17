package welcomehome.access;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import welcomehome.crypto.LinkTokenSigner;
import welcomehome.links.LinkStatus;
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

    GuestLinks(LinkTokenSigner signer, ResolvedLinkDao resolvedLinks, NukiConnections nukiConnections) {
        this.signer = signer;
        this.resolvedLinks = resolvedLinks;
        this.nukiConnections = nukiConnections;
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
        return link.lockActive() && nukiConnections.find(link.ownerId())
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
