package welcomehome.access;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import welcomehome.crypto.LinkTokenSigner;
import welcomehome.links.LinkStatus;

/**
 * Turns the token of a guest's URL into a link the app can trust: it resolves the link and accepts it only
 * if the token's MAC is valid for the link's current token version.
 */
@Service
class GuestLinks {

    private final LinkTokenSigner signer;
    private final ResolvedLinkDao resolvedLinks;

    GuestLinks(LinkTokenSigner signer, ResolvedLinkDao resolvedLinks) {
        this.signer = signer;
        this.resolvedLinks = resolvedLinks;
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
     * A link whose token was verified.
     *
     * @param link   the link and its lock
     * @param status the link's state
     */
    record GuestLink(ResolvedLink link, LinkStatus status) {

        /** @return whether the guest may try to open the door now */
        boolean canOpen() {
            return status == LinkStatus.ACTIVE && link.lockActive();
        }
    }
}
