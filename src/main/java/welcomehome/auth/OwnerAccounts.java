package welcomehome.auth;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(OwnerAccounts.class);

    /**
     * For how long the verified address an owner had still gets the notices about their account's security after
     * they change it: whoever changed it in the owner's session would otherwise be the only one told of the passkey
     * they add next, or of the link they create.
     */
    public static final Duration PREVIOUS_EMAIL_KEPT = Duration.ofDays(7);

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
     * @throws LastAdminException if the owner is the only admin, who stays so that an admin is always left
     */
    public boolean delete(UUID ownerId) {
        if (owners.findById(ownerId).isEmpty()) {
            return false;
        }
        // Before anything of theirs is touched. The deletion checks again, under a lock, for two sent at once.
        if (owners.isOnlyAdmin(ownerId)) {
            throw new LastAdminException();
        }
        events.publishEvent(new OwnerDeleting(ownerId));
        return owners.delete(ownerId);
    }

    /**
     * Lists every owner, for the admin panel.
     *
     * @return their emails and when they registered, the earliest first
     */
    public List<OwnerSummary> list() {
        return owners.findAll();
    }

    /**
     * Makes an owner an admin, for good: nothing takes the role away but deleting the account. Their role is read
     * when they sign in, so a session they have open stays an owner's until then.
     *
     * @param ownerId the owner
     * @return whether they became one, which an admin, or nobody, does not
     */
    public boolean makeAdmin(UUID ownerId) {
        var made = owners.makeAdmin(ownerId);
        if (made) {
            LOG.info("Owner {} is now an admin", ownerId);
        }
        return made;
    }

    /**
     * Finds an owner by their email.
     *
     * @param email an email
     * @return the owner's id, or empty if nobody has that email
     */
    public Optional<UUID> findIdByEmail(String email) {
        return owners.findByEmail(email).map(OwnerAccountDao.OwnerAccount::id);
    }

    /**
     * Tells whether anyone can administer the app yet.
     *
     * @return whether some owner is an admin
     */
    public boolean adminExists() {
        return owners.adminExists();
    }

    /** Tells whether the owner is an admin and no other owner is, so that leaving would leave no admin. */
    boolean isOnlyAdmin(UUID ownerId) {
        return owners.isOnlyAdmin(ownerId);
    }

    /**
     * Changes an owner's email at once: where their notices go, and the name their passkeys know them by. The new
     * address is unverified until its link is opened, as a new owner's is, and the previous one, if it was verified,
     * is told with {@link OwnerEmailChanged} and kept for {@link #PREVIOUS_EMAIL_KEPT}.
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
            language = owners.changeEmail(ownerId, newEmail, wasVerified, PREVIOUS_EMAIL_KEPT);
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
