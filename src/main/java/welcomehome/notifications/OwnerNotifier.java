package welcomehome.notifications;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import welcomehome.admin.InvitationService;
import welcomehome.admin.PasskeyRecoveryCreated;
import welcomehome.audit.AccessAttempted;
import welcomehome.audit.AccessOutcome;
import welcomehome.auth.EmailVerification;
import welcomehome.auth.EmailVerificationRequested;
import welcomehome.auth.PasskeyAdded;
import welcomehome.config.Languages;
import welcomehome.config.WelcomeHomeProperties;
import welcomehome.db.OwnerTransactions;
import welcomehome.nuki.NukiDisconnected;

/**
 * Emails an owner about their locks, as their preferences say: guests' attempts by outcome, and a Nuki
 * connection that stopped working. Also, whatever the preferences, about their account's security: a passkey
 * recovery link created for them, and a passkey added. Only to a verified address: the one email that goes to
 * an address not yet verified is the link that verifies it. Listeners run after the event's transaction
 * commits, mostly on another thread, so a guest never waits for an email; one that cannot be sent is logged and
 * dropped.
 */
@Component
class OwnerNotifier {

    private static final Logger log = LoggerFactory.getLogger(OwnerNotifier.class);

    private final Mailer mailer;
    private final NotificationPreferenceDao preferences;
    private final OwnerTransactions ownerTransactions;
    private final JdbcClient jdbc;
    private final WelcomeHomeProperties app;
    private final Cache<UUID, Boolean> recentlyDenied;
    private final MessageSource messages;

    OwnerNotifier(Mailer mailer, NotificationPreferenceDao preferences, OwnerTransactions ownerTransactions,
                  JdbcClient jdbc, WelcomeHomeProperties app, NotificationProperties properties,
                  MessageSource messages) {
        this.mailer = mailer;
        this.preferences = preferences;
        this.ownerTransactions = ownerTransactions;
        this.jdbc = jdbc;
        this.app = app;
        this.recentlyDenied = Caffeine.newBuilder().expireAfterWrite(properties.deniedQuietPeriod()).build();
        this.messages = messages;
    }

    @Async
    @TransactionalEventListener
    void on(AccessAttempted attempt) {
        if (!mailer.isConfigured()) {
            return;
        }
        var kind = NotificationKind.of(attempt.outcome());
        if (!wants(attempt.ownerId(), kind)) {
            return;
        }
        // At most one email per link and quiet period: a leaked link being tried must not flood the owner.
        if (kind == NotificationKind.DENIED && recentlyDenied.asMap().putIfAbsent(attempt.linkId(), true) != null) {
            return;
        }
        var keys = EmailKeys.of(attempt.outcome());
        findDetails(attempt).ifPresent(details ->
                send(details.recipient().email(), subject(keys, details), body(keys, details, attempt)));
    }

    @Async
    @TransactionalEventListener
    void on(NukiDisconnected disconnected) {
        if (!mailer.isConfigured() || !wants(disconnected.ownerId(), NotificationKind.DISCONNECTED)) {
            return;
        }
        findRecipient(disconnected.ownerId()).ifPresent(to -> send(to.email(),
                message(to, "notification.disconnected.subject"),
                message(to, "notification.disconnected.body", app.link("/settings/nuki")) + footer(to)));
    }

    /**
     * On the thread that created the link: the recovery command exits right after, which would cut an email
     * on another thread short. Without a transaction, as the link is created in none.
     */
    @TransactionalEventListener(fallbackExecution = true)
    void on(PasskeyRecoveryCreated created) {
        if (!mailer.isConfigured()) {
            return;
        }
        findRecipient(created.ownerId()).ifPresent(to -> send(to.email(),
                message(to, "account.recovery-created.subject"),
                message(to, "account.recovery-created.body", String.valueOf(InvitationService.VALIDITY.toHours()))
                        + accountFooter(to)));
    }

    /** Also without a transaction: Spring Security stores a passkey added from /settings/passkeys in none. */
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(PasskeyAdded added) {
        if (!mailer.isConfigured()) {
            return;
        }
        findRecipient(added.ownerEmail()).ifPresent(to -> send(to.email(),
                message(to, "account.passkey-added.subject"),
                message(to, "account.passkey-added.body", added.label(), app.link("/settings/passkeys")) + accountFooter(to)));
    }

    /** Also without a transaction, when the owner asks for the link again. */
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(EmailVerificationRequested requested) {
        if (!mailer.isConfigured()) {
            return;
        }
        var to = new Recipient(requested.email(), requested.language());
        send(to.email(), message(to, "account.verify.subject"),
                message(to, "account.verify.body", requested.link(),
                        String.valueOf(EmailVerification.VALIDITY.toDays())) + accountFooter(to));
    }

    private boolean wants(UUID ownerId, NotificationKind kind) {
        return ownerTransactions.execute(ownerId, () -> preferences.find(ownerId)).wants(kind);
    }

    private String subject(EmailKeys keys, AttemptDetails details) {
        return message(details.recipient(), keys.email() + ".subject", details.lockName(), details.label());
    }

    private String body(EmailKeys keys, AttemptDetails details, AccessAttempted attempt) {
        var to = details.recipient();
        var when = attempt.occurredAt().atZone(ZoneId.of(details.timeZone()));
        var at = message(to, "notification.when", format(to, "format.date", when), format(to, "format.time", when),
                details.timeZone());
        var reason = keys.reason();
        var why = reason == null ? "" : message(to, reason);
        var history = app.link("/locks/" + attempt.lockId() + "/history?link=" + attempt.linkId());
        return message(to, keys.email() + ".body", details.lockName(), details.label(), at, why)
                + "\n\n" + message(to, "notification.history", history) + "\n" + footer(to);
    }

    private String footer(Recipient to) {
        return "\n--\n" + message(to, "notification.footer", app.link("/settings/notifications")) + "\n";
    }

    private String accountFooter(Recipient to) {
        return "\n--\n" + message(to, "account.footer") + "\n";
    }

    private String message(Recipient to, String key, Object... arguments) {
        return messages.getMessage(key, arguments, to.language());
    }

    /** A date or time with the pattern the recipient's bundle gives. */
    private String format(Recipient to, String patternKey, ZonedDateTime when) {
        return DateTimeFormatter.ofPattern(message(to, patternKey), to.language()).format(when);
    }

    private void send(String to, String subject, String text) {
        try {
            mailer.send(to, subject, text);
        } catch (MailException e) {
            log.warn("Could not send a notification: {}", e.getMessage());
        }
    }

    private Optional<AttemptDetails> findDetails(AccessAttempted attempt) {
        return ownerTransactions.execute(attempt.ownerId(), () -> jdbc.sql("""
                        SELECT o.email, o.language, l.label, coalesce(d.name, k.name) AS lock_name, k.time_zone
                        FROM access_link l
                        JOIN lock k ON k.id = l.lock_id
                        LEFT JOIN lock d ON d.owner_id = l.owner_id AND d.id = CAST(:entranceLockId AS uuid)
                        JOIN owner o ON o.id = l.owner_id
                        WHERE l.id = :linkId AND l.owner_id = :ownerId AND o.email_verified_at IS NOT NULL
                        """)
                .param("linkId", attempt.linkId())
                .param("ownerId", attempt.ownerId())
                .param("entranceLockId", attempt.entranceLockId())
                .query((rs, row) -> new AttemptDetails(
                        new Recipient(rs.getString("email"), language(rs.getString("language"))),
                        rs.getString("label"), rs.getString("lock_name"), rs.getString("time_zone")))
                .optional());
    }

    private Optional<Recipient> findRecipient(UUID ownerId) {
        return jdbc.sql("SELECT email, language FROM owner WHERE id = :ownerId AND email_verified_at IS NOT NULL")
                .param("ownerId", ownerId)
                .query((rs, row) -> new Recipient(rs.getString("email"), language(rs.getString("language"))))
                .optional();
    }

    private Optional<Recipient> findRecipient(String email) {
        return jdbc.sql("SELECT email, language FROM owner WHERE email = :email AND email_verified_at IS NOT NULL")
                .param("email", email)
                .query((rs, row) -> new Recipient(rs.getString("email"), language(rs.getString("language"))))
                .optional();
    }

    /** An owner's language, or the default if the app no longer has texts for it. */
    private static Locale language(String tag) {
        return Languages.of(tag).orElse(Languages.DEFAULT);
    }

    /**
     * Whom an email goes to, and in which language.
     *
     * @param email    the owner's email
     * @param language the owner's language
     */
    private record Recipient(String email, Locale language) {
    }

    private record AttemptDetails(Recipient recipient, String label, String lockName, String timeZone) {
    }

    /**
     * Which email tells of an outcome, and what explains it: an opening, an unconfirmed opening, a failure or a
     * denial, the last two with why.
     */
    private record EmailKeys(String email, @Nullable String reason) {

        static EmailKeys of(AccessOutcome outcome) {
            var reason = "notification.reason." + outcome.code();
            return switch (outcome) {
                case OPENED -> new EmailKeys("notification.opened", null);
                case NOT_CONFIRMED -> new EmailKeys("notification.not-confirmed", null);
                case UNAVAILABLE, NUKI_ERROR, LOCK_FAILED, NIGHT_MODE, MISSING_PERMISSION ->
                        new EmailKeys("notification.failed", reason);
                case INVALID, TOO_MANY_ATTEMPTS, REVOKED, NOT_YET_VALID, EXPIRED ->
                        new EmailKeys("notification.denied", reason);
            };
        }
    }
}
