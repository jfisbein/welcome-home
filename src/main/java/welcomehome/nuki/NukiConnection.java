package welcomehome.nuki;

import java.time.LocalDateTime;

import org.jspecify.annotations.Nullable;

/**
 * An owner's Nuki connection, without its API token.
 *
 * @param status        whether the connection works
 * @param lastCheckedAt when Nuki last accepted or rejected the API token, in the server's time zone
 */
public record NukiConnection(ConnectionStatus status, @Nullable LocalDateTime lastCheckedAt) {
}
