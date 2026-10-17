package welcomehome.access;

import org.springframework.http.HttpStatus;
import welcomehome.audit.AccessResult;

/**
 * What a guest's attempt to open a door came to: the HTTP status, the code the guest page shows a message
 * for, and how the owner's history records it.
 */
enum OpenResult {

    /** The lock's log shows the unlatch. */
    OPENED(HttpStatus.OK, "opened", AccessResult.OK),
    /** Nuki accepted the unlatch, but the lock's log did not show it in time: the door may have opened. */
    NOT_CONFIRMED(HttpStatus.ACCEPTED, "not-confirmed", AccessResult.OK),
    INVALID(HttpStatus.NOT_FOUND, "invalid", AccessResult.DENIED),
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "too-many-attempts", AccessResult.DENIED),
    REVOKED(HttpStatus.GONE, "revoked", AccessResult.DENIED),
    NOT_YET_VALID(HttpStatus.FORBIDDEN, "not-yet-valid", AccessResult.DENIED),
    EXPIRED(HttpStatus.GONE, "expired", AccessResult.DENIED),
    /** The lock is inactive, Nuki is not connected, or Nuki refuses the owner's token: the owner must act. */
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", AccessResult.DENIED),
    /** Nuki failed or did not answer: trying again later may work. */
    NUKI_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "nuki-error", AccessResult.ERROR),
    /** The lock tried and failed, for example with its motor blocked. */
    LOCK_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "lock-failed", AccessResult.ERROR),
    /** The lock is in night mode and refused to open. */
    NIGHT_MODE(HttpStatus.SERVICE_UNAVAILABLE, "night-mode", AccessResult.ERROR);

    final HttpStatus status;
    final String code;
    final AccessResult accessResult;

    OpenResult(HttpStatus status, String code, AccessResult accessResult) {
        this.status = status;
        this.code = code;
        this.accessResult = accessResult;
    }
}
