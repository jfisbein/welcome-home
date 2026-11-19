package welcomehome.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The only admin cannot be deleted, by themselves or by anyone: nobody could then invite owners or recover an
 * account. The pages do not offer it; this is what a request sent anyway, or two sent at once, gets.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class LastAdminException extends RuntimeException {

    /** The only admin was about to be deleted. */
    public LastAdminException() {
        super("The only admin cannot be deleted");
    }
}
