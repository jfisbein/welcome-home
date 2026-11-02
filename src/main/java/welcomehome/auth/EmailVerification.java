package welcomehome.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import welcomehome.config.Languages;
import welcomehome.config.WelcomeHomeProperties;

/**
 * Whether an owner's email is theirs. A new owner gets a link at that address, a random token of 256 bits of
 * which only the SHA-256 is stored; opening it shows a button, and pressing it verifies the address. Until
 * then, the only email that goes there is the link. Asking for a new link replaces the previous one.
 */
@Service
public class EmailVerification {

    /** How long a link can be used. */
    @SuppressWarnings("CanonicalDuration")
    public static final Duration VALIDITY = Duration.ofDays(7);

    /** How long after sending a link another one can be asked for. */
    static final Duration RESEND_AFTER = Duration.ofMinutes(5);

    /** Longer input is not a token and is not even hashed. Real tokens have 43 characters. */
    private static final int MAX_TOKEN_LENGTH = 100;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailVerificationDao emailVerifications;
    private final WelcomeHomeProperties app;
    private final ApplicationEventPublisher events;

    EmailVerification(EmailVerificationDao emailVerifications, WelcomeHomeProperties app, ApplicationEventPublisher events) {
        this.emailVerifications = emailVerifications;
        this.app = app;
        this.events = events;
    }

    /**
     * Tells whether an owner's email is verified.
     *
     * @param ownerId the owner
     * @return whether they opened the link sent to their email, or registered before verification existed
     */
    public boolean isVerified(UUID ownerId) {
        return emailVerifications.isVerified(ownerId);
    }

    /** Sends a new owner, or one who asks again, a link to verify their email, replacing any earlier one. */
    void start(UUID ownerId) {
        var token = newToken();
        var owner = emailVerifications.saveLink(ownerId, hash(token), VALIDITY);
        owner.ifPresent(to -> events.publishEvent(new EmailVerificationRequested(to.email(),
                app.link("/email/verify/" + token), Languages.of(to.language()).orElse(Languages.DEFAULT))));
    }

    /**
     * Sends an unverified owner a new link, unless the last one went out less than {@link #RESEND_AFTER} ago.
     *
     * @return whether a link was sent
     */
    boolean resend(UUID ownerId) {
        var recent = emailVerifications.linkLastsLongerThan(ownerId, VALIDITY.minus(RESEND_AFTER));
        if (recent.isEmpty() || Boolean.TRUE.equals(recent.get())) {
            return false;
        }
        start(ownerId);
        return true;
    }

    /** @return the address a link is for, if it can still be used */
    Optional<String> pendingEmail(String token) {
        if (!isPlausible(token)) {
            return Optional.empty();
        }
        return emailVerifications.findPendingEmail(hash(token));
    }

    /**
     * Verifies the address a link is for, once.
     *
     * @return the address, or empty if the link is unknown, used or expired
     */
    Optional<String> verify(String token) {
        if (!isPlausible(token)) {
            return Optional.empty();
        }
        return emailVerifications.markVerified(hash(token));
    }

    private static boolean isPlausible(String token) {
        return !token.isEmpty() && token.length() <= MAX_TOKEN_LENGTH;
    }

    private static String newToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
