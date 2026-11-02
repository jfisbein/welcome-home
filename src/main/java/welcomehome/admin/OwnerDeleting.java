package welcomehome.admin;

import java.util.UUID;

/**
 * An owner is about to be deleted with everything of theirs: the last moment to use what is stored for them.
 * Published outside any transaction, and listeners run before the deletion.
 *
 * @param ownerId the owner
 */
public record OwnerDeleting(UUID ownerId) {
}
