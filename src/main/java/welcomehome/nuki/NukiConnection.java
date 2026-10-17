package welcomehome.nuki;

import java.time.LocalDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * An owner's Nuki connection, without its API token.
 *
 * @param id            the connection's id, which locks refer to
 * @param status        whether the connection works
 * @param lastCheckedAt when Nuki last accepted or rejected the API token, in the server's time zone
 */
public record NukiConnection(UUID id, ConnectionStatus status, @Nullable LocalDateTime lastCheckedAt) {
}
