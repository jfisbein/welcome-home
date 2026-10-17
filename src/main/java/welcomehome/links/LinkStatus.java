package welcomehome.links;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A link's state, computed from its dates and revocation, never stored. A link opens the door from
 * {@code validFrom}, inclusive, to {@code validUntil}, exclusive.
 */
public enum LinkStatus {

    /** Not valid yet. */
    PENDING,
    /** Valid now. */
    ACTIVE,
    /** No longer valid. */
    EXPIRED,
    /** Revoked by the owner, whatever its dates. */
    REVOKED;

    /**
     * Computes a link's state.
     *
     * @param validFrom  when the link starts to open the door
     * @param validUntil when it stops
     * @param revokedAt  when the owner revoked it, or {@code null}
     * @param now        the instant to compute the state at
     * @return the link's state at {@code now}
     */
    public static LinkStatus of(Instant validFrom, Instant validUntil, @Nullable Instant revokedAt, Instant now) {
        if (revokedAt != null) {
            return REVOKED;
        }
        if (now.isBefore(validFrom)) {
            return PENDING;
        }
        return now.isBefore(validUntil) ? ACTIVE : EXPIRED;
    }

    /**
     * Tells whether the link's URL is still worth giving to a guest.
     *
     * @return whether the link is pending or active
     */
    public boolean isUsable() {
        return this == PENDING || this == ACTIVE;
    }
}
