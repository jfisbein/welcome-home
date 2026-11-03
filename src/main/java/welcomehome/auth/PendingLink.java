package welcomehome.auth;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An invitation or a passkey recovery link that could still be used, for the admin panel.
 *
 * @param id          the link's id, to cancel it
 * @param recipient   the email a recovery link is for, or what the admin noted of whom an invitation is for
 * @param grantsAdmin whether the invitation makes its owner an admin
 * @param recovery    whether it is a passkey recovery link rather than an invitation
 * @param expiresAt   when it expires, in the server's time zone
 */
public record PendingLink(UUID id, String recipient, boolean grantsAdmin, boolean recovery, LocalDateTime expiresAt) {
}
