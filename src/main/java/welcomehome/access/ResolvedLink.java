package welcomehome.access;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A link and its lock as resolve_link returns them, before the token's MAC is checked: nothing here is to
 * be trusted until it is.
 */
record ResolvedLink(UUID linkId, UUID ownerId, UUID lockId, int tokenVersion, OffsetDateTime validFrom,
                    OffsetDateTime validUntil, @Nullable OffsetDateTime revokedAt, String lockName,
                    String lockTimeZone, boolean lockActive) {
}
