package welcomehome.auth;

/**
 * A passkey was added to an owner who already had one, from {@code /passkeys} or with a passkey recovery link.
 * Not published for a new owner's first passkey.
 *
 * @param ownerEmail the owner's email
 * @param label      the new passkey's name
 */
public record PasskeyAdded(String ownerEmail, String label) {
}
