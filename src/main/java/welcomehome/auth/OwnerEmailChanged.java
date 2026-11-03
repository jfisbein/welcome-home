package welcomehome.auth;

import java.util.Locale;

/**
 * An owner changed their email, which they had verified: the previous address is told, so that a change made by
 * someone else in their session does not go unnoticed. Published in the transaction that changes it.
 *
 * @param previousEmail the address the owner had, which gets the notice
 * @param newEmail      the address they have now, still to be verified
 * @param language      the owner's language
 */
public record OwnerEmailChanged(String previousEmail, String newEmail, Locale language) {
}
