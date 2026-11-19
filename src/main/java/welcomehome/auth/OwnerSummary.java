package welcomehome.auth;

import java.time.LocalDate;

/**
 * An owner as the admin panel lists them: never anything of their locks or links.
 *
 * @param email        the owner's email
 * @param registeredOn the day they registered
 */
public record OwnerSummary(String email, LocalDate registeredOn) {
}
