package welcomehome.auth;

import java.time.LocalDate;

/**
 * An owner as the admin panel lists them: never anything of their locks or links.
 *
 * @param email        the owner's email
 * @param registeredOn the day they registered
 * @param admin        whether they are an admin
 */
public record OwnerSummary(String email, LocalDate registeredOn, boolean admin) {
}
