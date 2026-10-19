package welcomehome.access;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import welcomehome.audit.AccessOutcome;

/**
 * The guest page's "Abrir" button. The token travels in the body, not in the URL, and the answer is a
 * short code for the page to explain.
 */
@RestController
class OpenController {

    private final OpenDoor openDoor;
    private final ClientIp clientIp;

    OpenController(OpenDoor openDoor, ClientIp clientIp) {
        this.openDoor = openDoor;
        this.clientIp = clientIp;
    }

    @PostMapping("/api/open")
    ResponseEntity<Map<String, String>> open(@RequestBody OpenRequest request, HttpServletRequest http) {
        var token = request.token() == null ? "" : request.token();
        var result = openDoor.open(token, clientIp.of(http), http.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.status(status(result)).body(Map.of("result", result.code()));
    }

    /** The page explains the outcome's code; the status is for anything else that reads the answer. */
    static HttpStatus status(AccessOutcome outcome) {
        return switch (outcome) {
            case OPENED -> HttpStatus.OK;
            case NOT_CONFIRMED -> HttpStatus.ACCEPTED;
            case INVALID -> HttpStatus.NOT_FOUND;
            case TOO_MANY_ATTEMPTS -> HttpStatus.TOO_MANY_REQUESTS;
            case REVOKED, EXPIRED -> HttpStatus.GONE;
            case NOT_YET_VALID -> HttpStatus.FORBIDDEN;
            case UNAVAILABLE, NUKI_ERROR, LOCK_FAILED, NIGHT_MODE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    /** Spring logs the body it reads at DEBUG, so toString leaves the token out. */
    record OpenRequest(@Nullable String token) {

        @Override
        public String toString() {
            return "OpenRequest[token=***]";
        }
    }
}
