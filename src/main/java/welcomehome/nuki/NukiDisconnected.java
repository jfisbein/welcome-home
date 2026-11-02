package welcomehome.nuki;

import java.util.UUID;

/**
 * Nuki rejected an owner's credentials and their connection went from OK to disconnected: their links cannot
 * open the door until they connect Nuki again, or Nuki accepts the credentials again. Published in the transaction that disconnects it.
 *
 * @param ownerId the owner
 */
public record NukiDisconnected(UUID ownerId) {
}
