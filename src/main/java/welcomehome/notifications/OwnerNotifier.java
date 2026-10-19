package welcomehome.notifications;

import java.time.ZoneId;
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
import welcomehome.auth.PasskeyAdded;
import welcomehome.config.WelcomeHomeProperties;
import welcomehome.db.OwnerTransactions;
import welcomehome.nuki.NukiDisconnected;

/**
 * Emails an owner about their locks, as their preferences say: guests' attempts by outcome, and a Nuki
 * connection that stopped working. Also, whatever the preferences, about their account's security: a passkey
 * recovery link created for them, and a passkey added. Listeners run after the event's transaction commits,
 * mostly on another thread, so a guest never waits for an email; one that cannot be sent is logged and dropped.
 */
@Component
class OwnerNotifier {

    private static final Logger log = LoggerFactory.getLogger(OwnerNotifier.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d/M/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    /** Owners have no language of their own yet: their emails use the default bundle's. */
    private static final Locale LANGUAGE = Locale.of("es");

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
                send(details.email(), subject(keys, details), body(keys, details, attempt)));
    }

    @Async
    @TransactionalEventListener
    void on(NukiDisconnected disconnected) {
        if (!mailer.isConfigured() || !wants(disconnected.ownerId(), NotificationKind.DISCONNECTED)) {
            return;
        }
        findEmail(disconnected.ownerId()).ifPresent(email -> send(email,
                message("notification.disconnected.subject"),
                message("notification.disconnected.body", app.link("/nuki/connection")) + footer()));
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
        findEmail(created.ownerId()).ifPresent(email -> send(email,
                message("account.recovery-created.subject"),
                message("account.recovery-created.body", String.valueOf(InvitationService.VALIDITY.toHours()))
                        + accountFooter()));
    }

    /** Also without a transaction: Spring Security stores a passkey added from /passkeys in none. */
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(PasskeyAdded added) {
        if (!mailer.isConfigured()) {
            return;
        }
        send(added.ownerEmail(), message("account.passkey-added.subject"),
                message("account.passkey-added.body", added.label(), app.link("/passkeys")) + accountFooter());
    }

    private boolean wants(UUID ownerId, NotificationKind kind) {
        return ownerTransactions.execute(ownerId, () -> preferences.find(ownerId)).wants(kind);
    }

    private String subject(EmailKeys keys, AttemptDetails details) {
        return message(keys.email() + ".subject", details.lockName(), details.label());
    }

    private String body(EmailKeys keys, AttemptDetails details, AccessAttempted attempt) {
        var when = attempt.occurredAt().atZone(ZoneId.of(details.timeZone()));
        var at = message("notification.when", DATE.format(when), TIME.format(when), details.timeZone());
        var reason = keys.reason();
        var why = reason == null ? "" : message(reason);
        var history = app.link("/locks/" + attempt.lockId() + "/history?link=" + attempt.linkId());
        return message(keys.email() + ".body", details.lockName(), details.label(), at, why)
                + "\n\n" + message("notification.history", history) + "\n" + footer();
    }

    private String footer() {
        return "\n--\n" + message("notification.footer", app.link("/notifications")) + "\n";
    }

    private String accountFooter() {
        return "\n--\n" + message("account.footer") + "\n";
    }

    private String message(String key, Object... arguments) {
        return messages.getMessage(key, arguments, LANGUAGE);
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
                        SELECT o.email, l.label, k.name AS lock_name, k.time_zone
                        FROM access_link l
                        JOIN lock k ON k.id = l.lock_id
                        JOIN owner o ON o.id = l.owner_id
                        WHERE l.id = :linkId AND l.owner_id = :ownerId
                        """)
                .param("linkId", attempt.linkId())
                .param("ownerId", attempt.ownerId())
                .query(AttemptDetails.class)
                .optional());
    }

    private Optional<String> findEmail(UUID ownerId) {
        return jdbc.sql("SELECT email FROM owner WHERE id = :ownerId")
                .param("ownerId", ownerId)
                .query(String.class)
                .optional();
    }

    private record AttemptDetails(String email, String label, String lockName, String timeZone) {
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
