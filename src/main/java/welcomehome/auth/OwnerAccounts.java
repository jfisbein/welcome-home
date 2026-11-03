package welcomehome.auth;

import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Owners' accounts as a whole: what the admin panel, and an owner themselves, may do to one.
 */
@Service
public class OwnerAccounts {

    private final OwnerAccountDao owners;
    private final ApplicationEventPublisher events;

    OwnerAccounts(OwnerAccountDao owners, ApplicationEventPublisher events) {
        this.owners = owners;
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
}
