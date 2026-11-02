package welcomehome.nuki;

import java.util.UUID;

/**
 * Nuki accepts again the credentials of an owner it had rejected, and their connection went from disconnected to OK:
 * their links can open the door again. Published in the transaction that reconnects it.
 *
 * @param ownerId the owner
 */
public record NukiReconnected(UUID ownerId) {
}
