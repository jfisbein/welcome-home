package welcomehome.auth;

import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import welcomehome.config.Languages;

/**
 * Owners' accounts as a whole: what the admin panel, and an owner themselves, may do to one.
 */
@Service
public class OwnerAccounts {

    private final OwnerAccountDao owners;
    private final PublicKeyCredentialUserEntityRepository userEntities;
    private final EmailVerification emailVerification;
    private final ApplicationEventPublisher events;

    OwnerAccounts(OwnerAccountDao owners, PublicKeyCredentialUserEntityRepository userEntities,
                  EmailVerification emailVerification, ApplicationEventPublisher events) {
        this.owners = owners;
        this.userEntities = userEntities;
        this.emailVerification = emailVerification;
        this.events = events;
    }

    /**
     * Deletes an owner with everything of theirs: passkeys, Nuki connection, locks, links and history. Publishes
     * {@link OwnerDeleting} first, outside any transaction, so that what is stored for them can still be used: their
     * Nuki authorization is revoked and their sessions end.
     *
     * @param ownerId the owner
     * @return whether there was such an owner
     */
    public boolean delete(UUID ownerId) {
        if (owners.findById(ownerId).isEmpty()) {
            return false;
        }
        events.publishEvent(new OwnerDeleting(ownerId));
        return owners.delete(ownerId);
    }

    /** Tells whether the owner is an admin and no other owner is, so that leaving would leave no admin. */
    boolean isOnlyAdmin(UUID ownerId) {
        return owners.isOnlyAdmin(ownerId);
    }

    /**
     * Changes an owner's email at once: where their notices go, and the name their passkeys know them by. The new
     * address is unverified until its link is opened, as a new owner's is, and the previous one, if it was verified,
     * is told with {@link OwnerEmailChanged}.
     *
     * @param newEmail an email already normalized
     * @return whether it changed, which it does not if it is the owner's already
     * @throws EmailTakenException if another owner has that email
     */
    @Transactional
    boolean changeEmail(UUID ownerId, String newEmail) {
        var owner = owners.findById(ownerId).orElseThrow(() -> new IllegalStateException("No such owner"));
        if (owner.email().equals(newEmail)) {
            return false;
        }
        if (owners.findByEmail(newEmail).isPresent() || userEntities.findByUsername(newEmail) != null) {
            throw new EmailTakenException();
        }
        var wasVerified = emailVerification.isVerified(ownerId);
        String language;
        try {
            language = owners.changeEmail(ownerId, newEmail);
        } catch (DuplicateKeyException e) {
            // Another owner took it at the same time and committed first: owner_email_key stops this one.
            throw new EmailTakenException(e);
        }
        emailVerification.start(ownerId);
        if (wasVerified) {
            events.publishEvent(new OwnerEmailChanged(owner.email(), newEmail,
                    Languages.of(language).orElse(Languages.DEFAULT)));
        }
        return true;
    }

    /** Another owner has the email asked for. */
    static final class EmailTakenException extends RuntimeException {

        EmailTakenException() {
            super("Another owner has this email");
        }

        EmailTakenException(Throwable cause) {
            super("Another owner has this email", cause);
        }
    }
}
