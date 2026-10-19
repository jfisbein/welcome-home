package welcomehome.auth;

import java.net.URI;
import java.util.Locale;

/**
 * An owner is to confirm that their email is theirs, by opening a link sent to it: the only email that goes
 * to an address not yet verified. Carries the link, whose token is stored only as a hash.
 *
 * @param email    the address to verify
 * @param link     the link to send, valid for {@link EmailVerification#VALIDITY}
 * @param language the owner's language
 */
public record EmailVerificationRequested(String email, URI link, Locale language) {
}
