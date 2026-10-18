package welcomehome.auth;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * Registration through an invitation cannot go on. The reason travels to the page as a short code.
 */
class RegistrationException extends RuntimeException {

    enum Reason {
        INVITATION_NOT_REDEEMABLE(HttpStatus.GONE, "invitation"),
        INVALID_EMAIL(HttpStatus.BAD_REQUEST, "email"),
        EMAIL_TAKEN(HttpStatus.CONFLICT, "email-taken"),
        NO_REGISTRATION_IN_PROGRESS(HttpStatus.BAD_REQUEST, "expired"),
        PASSKEY_REJECTED(HttpStatus.BAD_REQUEST, "passkey");

        final HttpStatus status;
        final String code;

        Reason(HttpStatus status, String code) {
            this.status = status;
            this.code = code;
        }
    }

    private final Reason reason;

    RegistrationException(Reason reason) {
        this(reason, null);
    }

    RegistrationException(Reason reason, @Nullable Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
