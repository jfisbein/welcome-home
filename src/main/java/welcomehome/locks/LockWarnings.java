package welcomehome.locks;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What is wrong with an owner's locks right now.
 *
 * @param byLock  the warnings of each lock with any, by the lock's id
 * @param unknown whether Nuki could not tell, so that no warnings does not mean that all is well
 */
public record LockWarnings(Map<UUID, List<LockWarning>> byLock, boolean unknown) {

    /**
     * Gets a lock's warnings.
     *
     * @param lockId the lock's id
     * @return what is wrong with the lock, if anything
     */
    public List<LockWarning> of(UUID lockId) {
        return byLock.getOrDefault(lockId, List.of());
    }
}
