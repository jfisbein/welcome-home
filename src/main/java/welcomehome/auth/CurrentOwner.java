package welcomehome.auth;

import java.util.UUID;

/**
 * The signed-in owner. Controllers get it as a method parameter: declare a {@code CurrentOwner} argument
 * and it is resolved from the session's authentication.
 *
 * @param id    the owner's id, for {@code OwnerTransactions}
 * @param email the owner's email
 */
public record CurrentOwner(UUID id, String email) {
}
