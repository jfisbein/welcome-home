package welcomehome.admin;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Creates single-use invitations, the only way to register as an owner.
 */
@Service
public class InvitationService {

    /** How long an invitation can be redeemed: 72 hours, as the invitation page says. */
    @SuppressWarnings("CanonicalDuration")
    public static final Duration VALIDITY = Duration.ofHours(72);

    private final InvitationDao invitations;
    private final String baseUrl;

    InvitationService(InvitationDao invitations, @Value("${welcomehome.base-url}") String baseUrl) {
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("No base URL configured: set BASE_URL");
        }
        this.invitations = invitations;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Creates an invitation valid for {@link #VALIDITY}.
     *
     * @param createdBy   the admin who creates it, or {@code null} for the bootstrap admin invitation
     * @param note        a reminder of who the invitation is for
     * @param grantsAdmin whether the owner who redeems it becomes an admin
     * @return the invitation, with the only copy of its URL
     */
    public CreatedInvitation createInvitation(@Nullable UUID createdBy, String note, boolean grantsAdmin) {
        var token = InvitationTokens.newToken();
        var inserted = invitations.insert(InvitationTokens.hash(token), note, createdBy, grantsAdmin, VALIDITY);
        return new CreatedInvitation(
                inserted.id(),
                URI.create(baseUrl + "/invite/" + token),
                inserted.expiresAt().toInstant());
    }

    /**
     * Looks up an invitation that can still be redeemed, without using it.
     *
     * @param token the token from the invitation link
     * @return the invitation, or empty if the token is unknown, used or expired
     */
    public Optional<RedeemableInvitation> findRedeemable(String token) {
        return isPlausible(token) ? invitations.findRedeemable(InvitationTokens.hash(token)) : Optional.empty();
    }

    /**
     * Uses an invitation, atomically: of two concurrent calls with the same token, only one succeeds.
     * Call it in the transaction that creates the owner, so that a failed registration rolls it back and
     * leaves the invitation usable.
     *
     * @param token the token from the invitation link
     * @return the invitation just used, or empty if the token is unknown, used or expired
     */
    public Optional<RedeemableInvitation> redeem(String token) {
        return isPlausible(token) ? invitations.markUsed(InvitationTokens.hash(token)) : Optional.empty();
    }

    private static boolean isPlausible(String token) {
        return token != null && !token.isEmpty() && token.length() <= InvitationTokens.MAX_LENGTH;
    }
}
