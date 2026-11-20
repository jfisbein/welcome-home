package welcomehome.nuki;

import java.time.ZonedDateTime;

import org.jspecify.annotations.Nullable;

/**
 * An owner's Nuki connection, without its tokens.
 *
 * @param status        whether the connection works
 * @param lastCheckedAt when Nuki last accepted or rejected the connection, in the server's time zone: an owner
 *                      has none of their own, as their locks have, and the page says which it shows
 */
public record NukiConnection(ConnectionStatus status, @Nullable ZonedDateTime lastCheckedAt) {
}
