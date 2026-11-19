package welcomehome.links;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import welcomehome.crypto.CryptoService;
import welcomehome.db.OwnerTransactions;

/**
 * The optional PIN of a link: a guest who holds its URL must type it after the slide, so that a leaked or forwarded
 * URL alone does not open the door. It is stored encrypted (AEAD, bound to its owner and link) so that the owner
 * can read it back, and only decrypted to compare or to show it to its owner. Reading it for a guest is only for a
 * link whose token was verified.
 * <p>
 * Four digits can be guessed, so wrong PINs are counted on the link's row, where a restart does not lose them: past
 * {@code welcomehome.links.pin-max-failures} the link is locked and refuses every attempt, a right PIN too, until
 * its owner edits it or gives it a new URL. A right PIN starts the count again, so that a guest's own slips over a
 * long stay never lock their link: the total is then of wrong PINs in a row, and whoever guesses between a guest's
 * openings gets as many again after each (the user's choice, 2026-11-19).
 */
@Service
public class LinkPins {

    /** The fewest digits a PIN has. */
    public static final int MIN_LENGTH = 4;
    /** The most digits a PIN has. */
    public static final int MAX_LENGTH = 8;
    private static final Pattern DIGITS = Pattern.compile("[0-9]{" + MIN_LENGTH + "," + MAX_LENGTH + "}");

    /** What a guest's PIN comes to. */
    public enum Check {
        /** The link has no PIN, so none is asked. */
        NOT_SET,
        /** The guest typed the link's PIN. */
        MATCHES,
        /** The guest typed none, or another one. */
        DOES_NOT_MATCH,
        /** The link has been given too many wrong PINs, this one perhaps the last: it compares no more. */
        LOCKED
    }

    private final LinkDao links;
    private final CryptoService crypto;
    private final OwnerTransactions ownerTransactions;
    private final ApplicationEventPublisher events;
    private final int maxFailures;

    LinkPins(LinkDao links, CryptoService crypto, OwnerTransactions ownerTransactions,
             ApplicationEventPublisher events, LinksProperties properties) {
        this.links = links;
        this.crypto = crypto;
        this.ownerTransactions = ownerTransactions;
        this.events = events;
        this.maxFailures = properties.pinMaxFailures();
    }

    /**
     * Tells whether a text can be a PIN.
     *
     * @param pin what an owner typed
     * @return whether it is {@value #MIN_LENGTH} to {@value #MAX_LENGTH} digits
     */
    public static boolean isValid(String pin) {
        return DIGITS.matcher(pin).matches();
    }

    /**
     * Tells whether a link asks for a PIN, in its owner's context: for a guest, only once the link's token is
     * verified.
     *
     * @param ownerId the link's owner
     * @param linkId  the link
     * @return whether the link has a PIN
     */
    public boolean isSet(UUID ownerId, UUID linkId) {
        return ownerTransactions.execute(ownerId, () -> links.findPin(ownerId, linkId)).isPresent();
    }

    /**
     * Compares what a guest typed with the link's PIN, in constant time, in the owner's context: only for a link
     * whose token was verified. A wrong PIN is counted, a right one starts the count again, and the wrong one that
     * spends the link's total locks it, which
     * {@link LinkPinLocked} tells. The link's row is locked meanwhile, so that guesses sent at once are counted one
     * after another and none is compared once the total is spent.
     *
     * @param ownerId the link's owner
     * @param linkId  the link
     * @param typed   what the guest typed, or null if nothing
     * @return whether the link has a PIN, and if so whether the guest typed it, or that it compares no more
     */
    public Check check(UUID ownerId, UUID linkId, @Nullable String typed) {
        return ownerTransactions.execute(ownerId, () -> {
            var found = links.findPinForUpdate(ownerId, linkId);
            if (found.isEmpty()) {
                return Check.NOT_SET;
            }
            var stored = found.get();
            if (isLocked(stored.failures())) {
                return Check.LOCKED;
            }
            var pin = crypto.decryptLinkPin(ownerId, linkId, stored.encrypted());
            // Compared as bytes, whatever the guest sent, and whatever its length.
            var same = typed != null && MessageDigest.isEqual(pin.getBytes(StandardCharsets.UTF_8),
                    typed.strip().getBytes(StandardCharsets.UTF_8));
            if (same) {
                if (stored.failures() > 0) {
                    links.resetPinFailures(ownerId, linkId);
                }
                return Check.MATCHES;
            }
            var failures = links.addPinFailure(ownerId, linkId);
            if (isLocked(failures)) {
                events.publishEvent(new LinkPinLocked(ownerId, stored.lockId(), linkId, failures));
                return Check.LOCKED;
            }
            return Check.DOES_NOT_MATCH;
        });
    }

    /** @return whether a link given that many wrong PINs refuses every attempt */
    boolean isLocked(int failures) {
        return failures >= maxFailures;
    }

    /** Starts the count of wrong PINs again: the owner edited the link or gave it a new URL; in their transaction. */
    void unlock(UUID ownerId, UUID linkId) {
        links.resetPinFailures(ownerId, linkId);
    }

    /** The link's PIN, for its owner to read back. */
    Optional<String> of(UUID ownerId, UUID linkId) {
        var stored = ownerTransactions.execute(ownerId, () -> links.findPin(ownerId, linkId));
        return stored.map(encrypted -> crypto.decryptLinkPin(ownerId, linkId, encrypted));
    }

    /** Sets the link's PIN, or takes it off with null; inside the owner's transaction. */
    void set(UUID ownerId, UUID linkId, @Nullable String pin) {
        if (pin == null) {
            links.clearPin(ownerId, linkId);
        } else {
            links.setPin(ownerId, linkId, crypto.encryptLinkPin(ownerId, linkId, pin));
        }
    }

    /**
     * Encrypts again with the keyset's primary key the owner's PINs that an earlier key encrypted.
     *
     * @return how many were encrypted again
     */
    int reEncrypt(UUID ownerId) {
        return ownerTransactions.execute(ownerId, () -> {
            var count = 0;
            for (var stored : links.findPins(ownerId).entrySet()) {
                var again = crypto.reEncryptLinkPin(ownerId, stored.getKey(), stored.getValue());
                if (again.isPresent()) {
                    links.setPin(ownerId, stored.getKey(), again.get());
                    count++;
                }
            }
            return count;
        });
    }
}
