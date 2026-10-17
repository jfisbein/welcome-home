package welcomehome.nuki;

/**
 * A smart lock in an owner's Nuki account.
 *
 * @param smartlockId the lock's Nuki id
 * @param name        the lock's name in the Nuki app
 */
public record SmartLock(long smartlockId, String name) {
}
