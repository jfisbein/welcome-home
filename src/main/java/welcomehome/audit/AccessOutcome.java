package welcomehome.audit;

import java.util.Arrays;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * What a guest's attempt to open a door came to. Its code is what the guest page explains and what the owner's
 * history stores, so codes never change once released.
 */
public enum AccessOutcome {

    /** The lock's log shows the unlatch. */
    OPENED("opened", AccessResult.OK),
    /**
     * Nuki accepted the unlatch, or did not answer it, and the lock's log did not show it in time: the door may
     * have opened.
     */
    NOT_CONFIRMED("not-confirmed", AccessResult.OK),
    /** No such link, or its MAC is wrong. Never recorded: there is no owner to record it for. */
    INVALID("invalid", AccessResult.DENIED),
    /** Rate limited, by IP address or by link. */
    TOO_MANY_ATTEMPTS("too-many-attempts", AccessResult.DENIED),
    /** The owner revoked the link. */
    REVOKED("revoked", AccessResult.DENIED),
    /** Before the link's dates. */
    NOT_YET_VALID("not-yet-valid", AccessResult.DENIED),
    /** After the link's dates. */
    EXPIRED("expired", AccessResult.DENIED),
    /** The lock is inactive, Nuki is not connected, or Nuki refuses the owner's token: the owner must act. */
    UNAVAILABLE("unavailable", AccessResult.DENIED),
    /** Nuki failed or did not answer, and did not run the unlatch: trying again later may work. */
    NUKI_ERROR("nuki-error", AccessResult.ERROR),
    /** The lock tried and failed, for example with its motor blocked. */
    LOCK_FAILED("lock-failed", AccessResult.ERROR),
    /** The lock is in night mode and refused to open. */
    NIGHT_MODE("night-mode", AccessResult.ERROR),
    /** Nuki refused the unlatch: the owner's API token lacks the permission to operate the lock. */
    MISSING_PERMISSION("missing-permission", AccessResult.ERROR);

    private final String code;
    private final AccessResult result;

    AccessOutcome(String code, AccessResult result) {
        this.code = code;
        this.result = result;
    }

    /**
     * The short code for this outcome, such as {@code expired} or {@code nuki-error}.
     *
     * @return the code
     */
    public String code() {
        return code;
    }

    /**
     * Whether the door opened, the app refused, or the lock or Nuki failed.
     *
     * @return the result
     */
    public AccessResult result() {
        return result;
    }

    /**
     * Finds the outcome with a code.
     *
     * @param code a code as {@link #code()} gives it, or {@code null}
     * @return the outcome, or empty if no outcome has that code
     */
    public static Optional<AccessOutcome> ofCode(@Nullable String code) {
        return Arrays.stream(values()).filter(outcome -> outcome.code.equals(code)).findFirst();
    }
}
