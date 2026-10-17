package welcomehome.nuki;

import java.util.List;
import java.util.UUID;

/**
 * The owners' Nuki smart locks, whatever the way to reach them: the Nuki Web API first, a Bridge or MQTT
 * later, without changes to the modules that use it.
 *
 * <p>Every method takes the owner whose Nuki connection to use: the provider finds that owner's
 * credentials itself, so they never leave this module. It trusts its callers to have checked that the
 * owner, or a valid access link of the owner, asks for it.
 */
public interface LockProvider {

    /**
     * Lists the owner's smart locks.
     *
     * @param ownerId the owner whose Nuki connection to use
     * @return the smart locks of the owner's Nuki account
     * @throws LockProviderException if the locks cannot be listed
     */
    List<SmartLock> listLocks(UUID ownerId);

    /**
     * Gets a lock's current state.
     *
     * @param ownerId     the owner whose Nuki connection to use
     * @param smartlockId the lock's Nuki id
     * @return the lock's state as last reported to Nuki
     * @throws LockProviderException if the state cannot be read
     */
    LockState getState(UUID ownerId, long smartlockId);

    /**
     * Asks the lock to unlatch, that is, to unlock and pull the latch so that the door opens with a push.
     * Nuki runs the action asynchronously: a normal return only means that it was accepted, and the
     * lock's state or log confirms it later.
     *
     * @param ownerId     the owner whose Nuki connection to use
     * @param smartlockId the lock's Nuki id
     * @throws LockProviderException if the action is not accepted
     */
    void unlatch(UUID ownerId, long smartlockId);

    /**
     * Gets the lock's latest log entries.
     *
     * @param ownerId     the owner whose Nuki connection to use
     * @param smartlockId the lock's Nuki id
     * @return the latest entries, newest first
     * @throws LockProviderException if the log cannot be read
     */
    List<LockLogEntry> getRecentLog(UUID ownerId, long smartlockId);
}
