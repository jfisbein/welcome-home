package welcomehome.nuki;

import java.util.List;
import java.util.UUID;

/**
 * An owner has just connected Nuki, and Nuki has listed their devices. Published after the connection is stored,
 * outside any transaction, and listeners run before the owner sees the next page.
 *
 * @param ownerId the owner
 * @param devices the smart locks and Openers that the connection gives access to
 */
public record NukiConnected(UUID ownerId, List<NukiDevice> devices) {
}
