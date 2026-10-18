package welcomehome.notifications;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import welcomehome.audit.AccessAttempted;
import welcomehome.db.OwnerTransactions;
import welcomehome.nuki.NukiDisconnected;

/**
 * Emails an owner about their locks, as their preferences say: guests' attempts by outcome, and a Nuki
 * connection that stopped working. Listeners run after the event's transaction commits, on another thread, so
 * a guest never waits for an email; one that cannot be sent is logged and dropped.
 */
@Component
class OwnerNotifier {

    private static final Logger log = LoggerFactory.getLogger(OwnerNotifier.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d/M/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** Why a guest with a valid link could not get in, by outcome code (see {@code access.OpenResult}). */
    private static final Map<String, String> FAILURES = Map.of(
            "not-confirmed", "Nuki aceptó la orden, pero la cerradura no confirmó que se abriera",
            "nuki-error", "Nuki no respondió",
            "lock-failed", "la cerradura no pudo abrir (por ejemplo, el motor está bloqueado)",
            "night-mode", "la cerradura está en modo nocturno",
            "unavailable", "la cerradura no está disponible: está inactiva o Nuki está desconectado");

    /** Why a link was refused, by outcome code. */
    private static final Map<String, String> DENIALS = Map.of(
            "not-yet-valid", "el enlace todavía no es válido",
            "expired", "el enlace ha caducado",
            "revoked", "el enlace está revocado",
            "too-many-attempts", "demasiados intentos seguidos");

    private final Mailer mailer;
    private final NotificationPreferenceDao preferences;
    private final OwnerTransactions ownerTransactions;
    private final JdbcClient jdbc;
    private final String baseUrl;
    private final Cache<UUID, Boolean> recentlyDenied;

    OwnerNotifier(Mailer mailer, NotificationPreferenceDao preferences, OwnerTransactions ownerTransactions,
                  JdbcClient jdbc, @Value("${welcomehome.base-url}") String baseUrl,
                  NotificationProperties properties) {
        this.mailer = mailer;
        this.preferences = preferences;
        this.ownerTransactions = ownerTransactions;
        this.jdbc = jdbc;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.recentlyDenied = Caffeine.newBuilder().expireAfterWrite(properties.deniedQuietPeriod()).build();
    }

    @Async
    @TransactionalEventListener
    void on(AccessAttempted attempt) {
        if (!mailer.isConfigured()) {
            return;
        }
        var kind = NotificationKind.of(attempt.result(), attempt.reason());
        if (!wants(attempt.ownerId(), kind)) {
            return;
        }
        // At most one email per link and quiet period: a leaked link being tried must not flood the owner.
        if (kind == NotificationKind.DENIED && recentlyDenied.asMap().putIfAbsent(attempt.linkId(), true) != null) {
            return;
        }
        findDetails(attempt).ifPresent(details ->
                send(details.email(), subject(kind, details), body(kind, details, attempt)));
    }

    @Async
    @TransactionalEventListener
    void on(NukiDisconnected disconnected) {
        if (!mailer.isConfigured() || !wants(disconnected.ownerId(), NotificationKind.DISCONNECTED)) {
            return;
        }
        findEmail(disconnected.ownerId()).ifPresent(email -> send(email,
                "Nuki ha rechazado tu API token",
                """
                        Nuki ha rechazado el API token que guardaste en welcome-home: tus enlaces no pueden abrir \
                        la puerta hasta que pegues uno nuevo.

                        Conecta Nuki de nuevo: %s/nuki/connection
                        """.formatted(baseUrl) + footer()));
    }

    private boolean wants(UUID ownerId, NotificationKind kind) {
        return ownerTransactions.execute(ownerId, () -> preferences.find(ownerId)).wants(kind);
    }

    private String subject(NotificationKind kind, AttemptDetails details) {
        return switch (kind) {
            case OPENED -> "%s: %s ha abierto la puerta".formatted(details.lockName(), details.label());
            case FAILED -> "%s: %s no ha podido abrir la puerta".formatted(details.lockName(), details.label());
            default -> "%s: intento denegado con el enlace de %s".formatted(details.lockName(), details.label());
        };
    }

    private String body(NotificationKind kind, AttemptDetails details, AccessAttempted attempt) {
        var when = attempt.occurredAt().atZone(ZoneId.of(details.timeZone()));
        var at = "el %s a las %s (hora de %s)".formatted(DATE.format(when), TIME.format(when), details.timeZone());
        var text = switch (kind) {
            case OPENED -> "%s ha abierto la puerta de %s %s.".formatted(details.label(), details.lockName(), at);
            case FAILED -> "%s ha intentado abrir la puerta de %s %s, pero no ha podido: %s."
                    .formatted(details.label(), details.lockName(), at,
                            FAILURES.getOrDefault(attempt.reason(), attempt.reason()));
            default -> """
                    Se ha intentado abrir la puerta de %s con el enlace de %s %s, y se ha denegado: %s.

                    Si no esperabas este intento, puede que el enlace se haya filtrado: regenéralo o revócalo. \
                    Durante un rato no te avisaremos de más intentos denegados con este enlace."""
                    .formatted(details.lockName(), details.label(), at,
                            DENIALS.getOrDefault(attempt.reason(), attempt.reason()));
        };
        return text + "\n\nHistorial de este enlace: %s/locks/%s/history?link=%s\n"
                .formatted(baseUrl, attempt.lockId(), attempt.linkId()) + footer();
    }

    private String footer() {
        return "\n--\nwelcome-home. Elige qué avisos recibes: %s/notifications\n".formatted(baseUrl);
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
}
