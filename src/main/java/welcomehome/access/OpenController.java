package welcomehome.access;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The guest page's "Abrir" button. The token travels in the body, not in the URL, and the answer is a
 * short code for the page to explain.
 */
@RestController
class OpenController {

    private final OpenDoor openDoor;

    OpenController(OpenDoor openDoor) {
        this.openDoor = openDoor;
    }

    @PostMapping("/api/open")
    ResponseEntity<Map<String, String>> open(@RequestBody OpenRequest request, HttpServletRequest http) {
        var token = request.token() == null ? "" : request.token();
        var result = openDoor.open(token, http.getRemoteAddr(), http.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.status(result.status).body(Map.of("result", result.code));
    }

    record OpenRequest(String token) {
    }
}
