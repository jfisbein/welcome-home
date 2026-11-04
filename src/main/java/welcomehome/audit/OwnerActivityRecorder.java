package welcomehome.audit;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import welcomehome.auth.OwnerSignedIn;
import welcomehome.config.ClientCountry;
import welcomehome.config.ClientIp;
import welcomehome.db.OwnerTransactions;
import welcomehome.links.LinkChanged;

/**
 * Records an owner's sign-ins and changes to their links in their activity, with where the request came from.
 * Listeners run on the request's thread, so that they can read it, and a link's change is recorded in its own
 * transaction: no change without its entry.
 */
@Component
class OwnerActivityRecorder {

    private final OwnerActivityDao activity;
    private final OwnerTransactions ownerTransactions;
    private final ClientIp clientIp;
    private final ClientCountry clientCountry;

    OwnerActivityRecorder(OwnerActivityDao activity, OwnerTransactions ownerTransactions, ClientIp clientIp,
                          ClientCountry clientCountry) {
        this.activity = activity;
        this.ownerTransactions = ownerTransactions;
        this.clientIp = clientIp;
        this.clientCountry = clientCountry;
    }

    @EventListener
    void on(OwnerSignedIn signedIn) {
        record(signedIn.ownerId(), ActivityKind.SIGNED_IN, null, null, signedIn.passkey());
    }

    @EventListener
    void on(LinkChanged changed) {
        record(changed.ownerId(), ActivityKind.of(changed.change()), changed.lockId(), changed.linkId(), null);
    }

    private void record(UUID ownerId, ActivityKind kind, @Nullable UUID lockId, @Nullable UUID linkId,
                        @Nullable String passkey) {
        var request = currentRequest();
        var ip = request == null ? null : clientIp.of(request);
        var country = request == null ? null : clientCountry.of(request).orElse(null);
        ownerTransactions.run(ownerId, () -> activity.insert(ownerId, kind, lockId, linkId, passkey, ip, country));
    }

    /** The request being answered, or null outside one, where there is nowhere to tell. */
    private static @Nullable HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }
}
