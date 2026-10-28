package welcomehome.nuki;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * comes twice, changes nothing. So is the PKCE code verifier, which never leaves the server until the exchange:
 * a code that leaks on its way back, through a log or the browser's history, is useless without it. The owner
 * is asked for the scopes of Nuki's Advanced API too, and sent to Nuki again without them if the client may not
 * ask for them. Every other outcome ends on the Nuki connection page. Without an OAuth2 client, both routes answer
 * 404.
 */
@Controller
class NukiAuthorizationController {

    /** Where Nuki sends the owner back: Nuki Web lists it among the client's callback URLs. */
    static final String CALLBACK_PATH = "/nuki/callback";

    /** The authorization under way, in the owner's session. */
    static final String PENDING = NukiAuthorizationController.class.getName() + ".pending";

    private static final Logger log = LoggerFactory.getLogger(NukiAuthorizationController.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 32 bytes are 43 characters in base64url, the shortest code verifier that RFC 7636 allows. */
    private static final int RANDOM_BYTES = 32;

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
        return toNuki(owner, session, true);
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
        if ("invalid_scope".equals(error) && expected.withAdvanced()) {
            log.info("Nuki's OAuth2 client may not ask for {}: authorizing without them, so the lock's log alone "
                    + "confirms openings", String.join(" ", NukiWebClient.ADVANCED_SCOPES));
            return toNuki(owner, session, false);
        }
        if (error != null || code == null) {
            // RFC 6749, section 4.1.2.1: access_denied when the owner said no.
            return backToTheConnection(redirect, "access_denied".equals(error) ? "cancelled" : "failed");
        }
        try {
            var locks = connections.connectAuthorized(owner.id(), code, expected.codeVerifier(),
                    app.link(CALLBACK_PATH));
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

    /** Starts an authorization in the owner's session and sends them to Nuki with it. */
    private String toNuki(CurrentOwner owner, HttpSession session, boolean withAdvanced) {
        var state = randomString();
        var codeVerifier = randomString();
        session.setAttribute(PENDING, new PendingAuthorization(owner.id(), state, codeVerifier, withAdvanced));
        return "redirect:" + nuki.authorizationUri(app.link(CALLBACK_PATH), state, codeVerifier, withAdvanced);
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

    private static String randomString() {
        var bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * An authorization that an owner started in this session.
     *
     * @param ownerId      the owner who started it
     * @param state        what Nuki has to send back
     * @param codeVerifier the PKCE secret that goes with the code when it is exchanged
     * @param withAdvanced whether it asks for the scopes of Nuki's Advanced API too
     */
    record PendingAuthorization(UUID ownerId, String state, String codeVerifier, boolean withAdvanced)
            implements Serializable {

        boolean matches(UUID owner, @Nullable String returnedState) {
            return ownerId.equals(owner) && returnedState != null && MessageDigest.isEqual(
                    state.getBytes(StandardCharsets.UTF_8), returnedState.getBytes(StandardCharsets.UTF_8));
        }
    }
}
