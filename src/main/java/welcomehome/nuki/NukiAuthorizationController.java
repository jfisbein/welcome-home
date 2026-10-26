package welcomehome.nuki;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.auth.CurrentOwner;
import welcomehome.config.WelcomeHomeProperties;

/**
 * Connecting Nuki with OAuth2: the owner is sent to Nuki to authorize welcome-home, and Nuki sends them back to
 * the callback with a code, which is exchanged for the owner's tokens. The state sent along is kept in the
 * owner's session and taken out on the way back, so a callback that this session did not start, or one that
 * comes twice, changes nothing. Every outcome ends on the Nuki connection page. Without an OAuth2 client, both
 * routes answer 404.
 */
@Controller
class NukiAuthorizationController {

    /** Where Nuki sends the owner back: Nuki Web lists it among the client's callback URLs. */
    static final String CALLBACK_PATH = "/nuki/callback";

    /** The authorization under way, in the owner's session. */
    static final String PENDING = NukiAuthorizationController.class.getName() + ".pending";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int STATE_BYTES = 32;

    private final NukiConnections connections;
    private final NukiWebClient nuki;
    private final NukiProperties properties;
    private final WelcomeHomeProperties app;

    NukiAuthorizationController(NukiConnections connections, NukiWebClient nuki, NukiProperties properties,
                                WelcomeHomeProperties app) {
        this.connections = connections;
        this.nuki = nuki;
        this.properties = properties;
        this.app = app;
    }

    @GetMapping("/settings/nuki/oauth")
    String authorize(CurrentOwner owner, HttpSession session) {
        requireClient();
        var state = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        session.setAttribute(PENDING, new PendingAuthorization(owner.id(), state));
        return "redirect:" + nuki.authorizationUri(app.link(CALLBACK_PATH), state);
    }

    @GetMapping(CALLBACK_PATH)
    String callback(CurrentOwner owner, @RequestParam(required = false) @Nullable String state,
                    @RequestParam(required = false) @Nullable String code,
                    @RequestParam(required = false) @Nullable String error,
                    HttpSession session, RedirectAttributes redirect) {
        requireClient();
        var pending = session.getAttribute(PENDING);
        session.removeAttribute(PENDING);
        if (!(pending instanceof PendingAuthorization expected) || !expected.matches(owner.id(), state)) {
            return backToTheConnection(redirect, "failed");
        }
        if (error != null || code == null) {
            // RFC 6749, section 4.1.2.1: access_denied when the owner said no.
            return backToTheConnection(redirect, "access_denied".equals(error) ? "cancelled" : "failed");
        }
        try {
            var locks = connections.connectAuthorized(owner.id(), code, app.link(CALLBACK_PATH));
            redirect.addFlashAttribute("connectedLocks", locks.stream().map(SmartLock::name).toList());
            return "redirect:/settings/nuki";
        } catch (LockProviderException e) {
            return backToTheConnection(redirect, switch (e.reason()) {
                case NUKI_ERROR, NO_ANSWER -> "unavailable";
                case MISSING_PERMISSION -> "missing-permission";
                case NOT_CONNECTED, ACCESS_DENIED, LOCK_NOT_FOUND -> "failed";
            });
        }
    }

    private void requireClient() {
        if (!properties.oauth().enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static String backToTheConnection(RedirectAttributes redirect, String error) {
        redirect.addFlashAttribute("authorizationError", error);
        return "redirect:/settings/nuki";
    }

    private static byte[] randomBytes() {
        var bytes = new byte[STATE_BYTES];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * An authorization that an owner started in this session.
     *
     * @param ownerId the owner who started it
     * @param state   what Nuki has to send back
     */
    record PendingAuthorization(UUID ownerId, String state) implements Serializable {

        boolean matches(UUID owner, @Nullable String returnedState) {
            return ownerId.equals(owner) && returnedState != null && MessageDigest.isEqual(
                    state.getBytes(StandardCharsets.UTF_8), returnedState.getBytes(StandardCharsets.UTF_8));
        }
    }
}
