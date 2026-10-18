package welcomehome.nuki;

import java.util.UUID;

/**
 * Nuki rejected an owner's API token and their connection went from OK to disconnected: their links cannot
 * open the door until they paste a new token. Published in the transaction that disconnects it.
 *
 * @param ownerId the owner
 */
public record NukiDisconnected(UUID ownerId) {
}
