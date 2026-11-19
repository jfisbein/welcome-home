package welcomehome.links;

import java.time.Instant;
import java.time.OffsetDateTime;

import org.jspecify.annotations.Nullable;

/**
 * A link's state, computed from its dates, revocation and pause, never stored. A link opens the door from
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
    REVOKED,
    /** Paused by the owner before its end: it opens nothing until they resume it. */
    PAUSED;

    /**
     * Computes a link's state.
     *
     * @param validFrom  when the link starts to open the door
     * @param validUntil when it stops
     * @param revokedAt  when the owner revoked or paused it, or {@code null}
     * @param paused     whether that was a pause, which the owner can end; a paused link past its end has expired
     * @param now        the instant to compute the state at
     * @return the link's state at {@code now}
     */
    public static LinkStatus of(Instant validFrom, Instant validUntil, @Nullable Instant revokedAt, boolean paused,
                                Instant now) {
        if (revokedAt != null && !paused) {
            return REVOKED;
        }
        if (revokedAt != null) {
            return now.isBefore(validUntil) ? PAUSED : EXPIRED;
        }
        if (now.isBefore(validFrom)) {
            return PENDING;
        }
        return now.isBefore(validUntil) ? ACTIVE : EXPIRED;
    }

    /**
     * Computes a link's state from its dates as the database gives them.
     *
     * @param validFrom  when the link starts to open the door
     * @param validUntil when it stops
     * @param revokedAt  when the owner revoked or paused it, or {@code null}
     * @param paused     whether that was a pause
     * @param now        the instant to compute the state at
     * @return the link's state at {@code now}
     */
    public static LinkStatus of(OffsetDateTime validFrom, OffsetDateTime validUntil,
                                @Nullable OffsetDateTime revokedAt, boolean paused, Instant now) {
        return of(validFrom.toInstant(), validUntil.toInstant(), revokedAt == null ? null : revokedAt.toInstant(),
                paused, now);
    }

    /**
     * Tells whether the link's URL is still worth giving to a guest.
     *
     * @return whether the link is pending or active
     */
    public boolean isUsable() {
        return this == PENDING || this == ACTIVE;
    }

    /**
     * Tells whether the link is over: nothing its owner can do makes it open again but new dates, or nothing at all.
     *
     * @return whether the link expired or was revoked
     */
    public boolean isEnded() {
        return this == EXPIRED || this == REVOKED;
    }
}
