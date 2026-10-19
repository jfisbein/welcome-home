package welcomehome.nuki;

import org.jspecify.annotations.Nullable;

/**
 * A {@link LockProvider} call failed. The reason tells callers what to show: an owner can fix
 * {@link Reason#NOT_CONNECTED}, {@link Reason#ACCESS_DENIED} and {@link Reason#MISSING_PERMISSION} by
 * pasting a new API token, and a guest sees "not available" for any of them.
 */
public class LockProviderException extends RuntimeException {

    /** Why the call failed. */
    public enum Reason {
        /** The owner has no Nuki connection, or it is disconnected. */
        NOT_CONNECTED,
        /** Nuki rejected the owner's credentials (401): they were revoked. The connection is now disconnected. */
        ACCESS_DENIED,
        /** Nuki accepted the credentials but they lack the permission for this call (403). */
        MISSING_PERMISSION,
        /** The lock is not in the owner's Nuki account. */
        LOCK_NOT_FOUND,
        /**
         * Nuki could not be reached, answered with an error or kept rate limiting. For an action, Nuki did not
         * run it.
         */
        NUKI_ERROR,
        /**
         * An action was sent, but Nuki gave no answer in time or failed with a server error: it may have run
         * it. Never retried, as the door could open twice; the lock's log tells what happened.
         */
        NO_ANSWER
    }

    /** Why the call failed. */
    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason why the call failed
     */
    public LockProviderException(Reason reason) {
        this(reason, null);
    }

    /**
     * Creates the exception with its cause.
     *
     * @param reason why the call failed
     * @param cause  the underlying error, if any
     */
    public LockProviderException(Reason reason, @Nullable Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    /**
     * Tells why the call failed.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }
}
