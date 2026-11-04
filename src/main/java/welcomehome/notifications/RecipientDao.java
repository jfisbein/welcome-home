package welcomehome.notifications;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import welcomehome.audit.AccessAttempted;
import welcomehome.config.Languages;

/**
 * Whom an email goes to: only an owner whose address is verified. owner has no Row-Level Security; what is read of
 * a link and its locks takes {@code OwnerTransactions} for the same owner, and filters by owner_id as well.
 */
@Repository
class RecipientDao {

    private final JdbcClient jdbc;

    RecipientDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AttemptDetails> findAttemptDetails(AccessAttempted attempt) {
        return jdbc.sql("""
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
                .optional();
    }

    /** A link of the owner's, with what an email about it says, if the owner's address is verified. */
    Optional<LinkDetails> findLinkDetails(UUID ownerId, UUID linkId) {
        return jdbc.sql("""
                        SELECT o.email, o.language, l.label, k.name AS lock_name, k.time_zone, l.valid_from, l.valid_until
                        FROM access_link l
                        JOIN lock k ON k.owner_id = l.owner_id AND k.id = l.lock_id
                        JOIN owner o ON o.id = l.owner_id
                        WHERE l.id = :linkId AND l.owner_id = :ownerId AND o.email_verified_at IS NOT NULL
                        """)
                .param("linkId", linkId)
                .param("ownerId", ownerId)
                .query((rs, row) -> new LinkDetails(
                        new Recipient(rs.getString("email"), language(rs.getString("language"))),
                        rs.getString("label"), rs.getString("lock_name"), rs.getString("time_zone"),
                        rs.getObject("valid_from", OffsetDateTime.class).toInstant(),
                        rs.getObject("valid_until", OffsetDateTime.class).toInstant()))
                .optional();
    }

    Optional<Recipient> find(UUID ownerId) {
        return jdbc.sql("SELECT email, language FROM owner WHERE id = :ownerId AND email_verified_at IS NOT NULL")
                .param("ownerId", ownerId)
                .query((rs, row) -> new Recipient(rs.getString("email"), language(rs.getString("language"))))
                .optional();
    }

    Optional<Recipient> find(String email) {
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
    record Recipient(String email, Locale language) {
    }

    record AttemptDetails(Recipient recipient, String label, String lockName, String timeZone) {
    }

    record LinkDetails(Recipient recipient, String label, String lockName, String timeZone, Instant validFrom,
                       Instant validUntil) {
    }
}
