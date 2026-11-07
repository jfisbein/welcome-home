package welcomehome.access;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import welcomehome.audit.AccessOutcome;
import welcomehome.config.ClientCountry;
import welcomehome.config.ClientIp;

/**
 * The guest page's slide to open, for the link's lock or its building entrance. The token travels in the body,
 * not in the URL, and the answer is a short code for the page to explain, with, for a door that checks where its
 * guests are, how far from it the guest was. The guest's position, sent only for such a door, goes no further than
 * that distance.
 */
@RestController
class OpenController {

    private final OpenDoor openDoor;
    private final ClientIp clientIp;
    private final ClientCountry clientCountry;

    OpenController(OpenDoor openDoor, ClientIp clientIp, ClientCountry clientCountry) {
        this.openDoor = openDoor;
        this.clientIp = clientIp;
        this.clientCountry = clientCountry;
    }

    @PostMapping("/api/open")
    ResponseEntity<Map<String, Object>> open(@RequestBody OpenRequest request, HttpServletRequest http) {
        var token = request.token() == null ? "" : request.token();
        var position = request.position() == null ? null : GuestPosition.of(request.position().latitude(),
                request.position().longitude(), request.position().accuracy());
        var opening = openDoor.open(token, Door.of(request.door()), clientIp.of(http),
                clientCountry.of(http).orElse(null), position, Boolean.TRUE.equals(request.confirmed()),
                request.pin());
        var body = new LinkedHashMap<String, Object>();
        body.put("result", opening.outcome().code());
        if (opening.distanceM() != null) {
            body.put("distance", opening.distanceM());
        }
        return ResponseEntity.status(status(opening.outcome())).body(body);
    }

    /** The page explains the outcome's code; the status is for anything else that reads the answer. */
    static HttpStatus status(AccessOutcome outcome) {
        return switch (outcome) {
            case OPENED -> HttpStatus.OK;
            case NOT_CONFIRMED -> HttpStatus.ACCEPTED;
            case INVALID -> HttpStatus.NOT_FOUND;
            case TOO_MANY_ATTEMPTS -> HttpStatus.TOO_MANY_REQUESTS;
            case REVOKED, EXPIRED -> HttpStatus.GONE;
            case NOT_YET_VALID, OUTSIDE_SCHEDULE, TOO_FAR -> HttpStatus.FORBIDDEN;
            case CONFIRM_LOCATION, PIN_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
            case WRONG_PIN -> HttpStatus.UNAUTHORIZED;
            case UNAVAILABLE, NUKI_ERROR, LOCK_FAILED, NIGHT_MODE, MISSING_PERMISSION -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    /**
     * Spring logs the body it reads at DEBUG, so toString leaves the token, the PIN and the position out.
     *
     * @param door      {@code entrance} to open the building entrance of the link's lock, or anything else, absent
     *                  included, to open the lock
     * @param position  where the guest's browser says they are, for a door that checks it, or null if it did not
     *                  say
     * @param confirmed whether the guest confirmed being in front of the door, when asked to
     * @param pin       what the guest typed, when the link asks for a PIN
     */
    record OpenRequest(@Nullable String token, @Nullable String door, @Nullable PositionRequest position,
                       @Nullable Boolean confirmed, @Nullable String pin) {

        @Override
        public String toString() {
            return "OpenRequest[token=***, door=" + door + ", position=" + (position == null ? null : "***")
                    + ", confirmed=" + confirmed + ", pin=" + (pin == null ? null : "***") + "]";
        }
    }

    /**
     * A position as the browser gives it: degrees, and its accuracy in metres. Any part missing or impossible makes
     * it unknown.
     */
    record PositionRequest(@Nullable Double latitude, @Nullable Double longitude, @Nullable Double accuracy) {

        @Override
        public String toString() {
            return "PositionRequest[***]";
        }
    }
}
