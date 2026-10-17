package welcomehome.access;

import org.springframework.http.HttpStatus;

/**
 * What a guest's attempt to open a door came to, with the HTTP status and the code the guest page shows a
 * message for.
 */
enum OpenResult {

    REQUESTED(HttpStatus.OK, "requested"),
    INVALID(HttpStatus.NOT_FOUND, "invalid"),
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "too-many-attempts"),
    REVOKED(HttpStatus.GONE, "revoked"),
    NOT_YET_VALID(HttpStatus.FORBIDDEN, "not-yet-valid"),
    EXPIRED(HttpStatus.GONE, "expired"),
    /** The lock is inactive, Nuki is not connected, or Nuki refuses the owner's token: the owner must act. */
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "unavailable"),
    /** Nuki failed or did not answer: trying again later may work. */
    NUKI_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "nuki-error");

    final HttpStatus status;
    final String code;

    OpenResult(HttpStatus status, String code) {
        this.status = status;
        this.code = code;
    }
}
