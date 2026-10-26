package welcomehome.nuki;

import java.time.LocalDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * An owner's Nuki connection, without its tokens.
 *
 * @param id            the connection's id, which locks refer to
 * @param credential    how the owner connected Nuki
 * @param status        whether the connection works
 * @param lastCheckedAt when Nuki last accepted or rejected the connection, in the server's time zone
 */
public record NukiConnection(UUID id, NukiCredential credential, ConnectionStatus status,
                             @Nullable LocalDateTime lastCheckedAt) {
}
