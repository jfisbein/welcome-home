package welcomehome.auth;

import java.time.Duration;
import java.util.Locale;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import welcomehome.admin.OwnerDeleting;

/**
 * The signed-in sessions of each owner, so that they can be ended when a passkey or the owner is gone: a lost phone
 * would otherwise stay signed in, and a deleted admin would keep their role, until the session expires. Kept in
 * memory, as the sessions are.
 */
@Component
class OwnerSessions {

    /** Longer than a session lasts: one that made no request for this long has expired by itself. */
    private static final Duration KEPT_FOR = Duration.ofDays(1);

    private final Cache<String, SignedIn> sessions = Caffeine.newBuilder().expireAfterAccess(KEPT_FOR).build();
    private final OwnerAccountDao owners;

    OwnerSessions(OwnerAccountDao owners) {
        this.owners = owners;
    }

    /** Notes a request of a session signed in as this owner. */
    void seen(String email, HttpSession session) {
        sessions.put(session.getId(), new SignedIn(key(email), session));
    }

    /**
     * Ends the owner's sessions but one.
     *
     * @param keptSessionId the id of the session to keep, usually the one asking, if any
     */
    void endOthers(String email, @Nullable String keptSessionId) {
        var owner = key(email);
        sessions.asMap().forEach((id, signedIn) -> {
            if (signedIn.email().equals(owner) && !id.equals(keptSessionId)) {
                sessions.invalidate(id);
                end(signedIn.session());
            }
        });
    }

    @EventListener
    void on(OwnerDeleting deleting) {
        owners.findById(deleting.ownerId()).ifPresent(owner -> endOthers(owner.email(), null));
    }

    private static void end(HttpSession session) {
        try {
            session.invalidate();
        } catch (IllegalStateException alreadyEnded) {
            // It expired by itself, or its owner signed out: nothing is left to end.
        }
    }

    private static String key(String email) {
        return email.toLowerCase(Locale.ROOT);
    }

    private record SignedIn(String email, HttpSession session) {
    }
}
