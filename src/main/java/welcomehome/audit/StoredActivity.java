package welcomehome.audit;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * An entry of an owner's activity, with the names of its link and lock while they exist.
 *
 * @param kind      an {@link ActivityKind}'s name
 * @param linkLabel the link's label, or {@code null} if the entry is not about a link or the link is gone
 * @param lockName  the link's lock, or {@code null} if the entry is not about a link
 * @param passkey   the passkey a sign-in used, if known
 * @param ip        where it was made from, if still kept
 * @param country   the country it was made from, as an ISO 3166 code or T1 for Tor, if known
 */
record StoredActivity(UUID id, OffsetDateTime occurredAt, String kind, @Nullable UUID linkId,
                      @Nullable String linkLabel, @Nullable String lockName, @Nullable String passkey,
                      @Nullable String ip, @Nullable String country) {
}
