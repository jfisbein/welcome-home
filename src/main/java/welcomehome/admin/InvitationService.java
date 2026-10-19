package welcomehome.admin;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import welcomehome.config.WelcomeHomeProperties;

/**
 * Creates single-use invitations, the only way to register as an owner, and passkey recovery links, which
 * add a passkey to an existing owner. Both are redeemed at {@code /invite/{token}}.
 */
@Service
public class InvitationService {

    /** How long an invitation or recovery link can be redeemed: 72 hours, as the admin panel says. */
    @SuppressWarnings("CanonicalDuration")
    public static final Duration VALIDITY = Duration.ofHours(72);

    private final InvitationDao invitations;
    private final WelcomeHomeProperties app;
    private final ApplicationEventPublisher events;

    InvitationService(InvitationDao invitations, WelcomeHomeProperties app, ApplicationEventPublisher events) {
        this.invitations = invitations;
        this.app = app;
        this.events = events;
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
        return create(createdBy, note, grantsAdmin, null);
    }

    /**
     * Creates a passkey recovery link valid for {@link #VALIDITY}: whoever opens it adds a passkey to the
     * owner, who keeps the passkeys they already have. Publishes a {@link PasskeyRecoveryCreated}, so that the
     * owner learns of it.
     *
     * @param createdBy the admin who creates it, or {@code null} when created from the server's command line
     * @param ownerId   the owner who gets the new passkey
     * @return the link, with the only copy of its URL
     */
    public CreatedInvitation createPasskeyRecovery(@Nullable UUID createdBy, UUID ownerId) {
        var created = create(createdBy, "Passkey recovery", false, ownerId);
        events.publishEvent(new PasskeyRecoveryCreated(ownerId));
        return created;
    }

    private CreatedInvitation create(@Nullable UUID createdBy, String note, boolean grantsAdmin,
                                     @Nullable UUID recoversOwnerId) {
        var token = InvitationTokens.newToken();
        var inserted = invitations.insert(InvitationTokens.hash(token), note, createdBy, grantsAdmin,
                recoversOwnerId, VALIDITY);
        return new CreatedInvitation(
                inserted.id(),
                app.link("/invite/" + token),
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
