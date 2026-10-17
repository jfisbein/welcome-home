package welcomehome.locks;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;

/**
 * Reads an owner's locks, for this module and for others such as links.
 */
@Service
public class Locks {

    private final LockDao locks;
    private final OwnerTransactions ownerTransactions;

    Locks(LockDao locks, OwnerTransactions ownerTransactions) {
        this.locks = locks;
        this.ownerTransactions = ownerTransactions;
    }

    /**
     * Lists the owner's locks, active ones first.
     *
     * @param ownerId the owner
     * @return the owner's locks
     */
    public List<OwnerLock> findAll(UUID ownerId) {
        return ownerTransactions.execute(ownerId, () -> locks.findAll(ownerId));
    }

    /**
     * Finds one of the owner's locks.
     *
     * @param ownerId the owner
     * @param lockId  the lock's id
     * @return the lock, or empty if the owner has no lock with that id
     */
    public Optional<OwnerLock> find(UUID ownerId, UUID lockId) {
        return ownerTransactions.execute(ownerId, () -> locks.find(ownerId, lockId));
    }
}
