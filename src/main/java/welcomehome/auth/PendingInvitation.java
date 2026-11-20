package welcomehome.auth;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * An invitation or a passkey recovery link that could still be used, for the admin panel.
 *
 * @param id          the link's id, to cancel it
 * @param recipient   the email a recovery link is for, or what the admin noted of whom an invitation is for
 * @param grantsAdmin whether the invitation makes its owner an admin
 * @param recovery    whether it is a passkey recovery link rather than an invitation
 * @param expiresAt   when it expires, in the server's time zone, which the page says
 */
public record PendingInvitation(UUID id, String recipient, boolean grantsAdmin, boolean recovery,
                                ZonedDateTime expiresAt) {
}
