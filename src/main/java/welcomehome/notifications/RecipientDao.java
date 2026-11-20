package welcomehome.notifications;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import welcomehome.audit.AccessAttempted;
import welcomehome.config.Languages;

/**
 * Whom an email goes to: only an owner whose address is verified. A notice about an account's security also goes
 * to the verified address the owner had, for some days after they change it
 * ({@link welcomehome.auth.OwnerAccounts#PREVIOUS_EMAIL_KEPT}), whether or not the new one is verified yet. owner has no Row-Level Security; what is read of
 * a link and its locks takes {@code OwnerTransactions} for the same owner, and filters by owner_id as well.
 */
@Repository
class RecipientDao {

    /** The owner's verified address first, then the one they had. */
    private static final String ACCOUNT_RECIPIENTS = """
            SELECT email, language, false AS previous FROM owner WHERE id = :owner AND email_verified_at IS NOT NULL
            UNION ALL
            SELECT previous_email, language, true FROM owner
            WHERE id = :owner AND previous_email_until > now() AND previous_email <> email
            ORDER BY previous
            """;

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

    /** A link of the owner's, with what a notice about their account says of it, if anyone is to get it. */
    Optional<LinkDetails> findLinkDetails(UUID ownerId, UUID linkId) {
        var recipients = findForAccount(ownerId);
        if (recipients.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT l.label, k.name AS lock_name, k.time_zone, l.valid_from, l.valid_until
                        FROM access_link l
                        JOIN lock k ON k.owner_id = l.owner_id AND k.id = l.lock_id
                        WHERE l.id = :linkId AND l.owner_id = :ownerId
                        """)
                .param("linkId", linkId)
                .param("ownerId", ownerId)
                .query((rs, row) -> new LinkDetails(recipients,
                        rs.getString("label"), rs.getString("lock_name"), rs.getString("time_zone"),
                        rs.getObject("valid_from", OffsetDateTime.class).toInstant(),
                        rs.getObject("valid_until", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * Whom a notice about an owner's account goes to: their address if it is verified, and the one they had if
     * they changed it lately.
     */
    List<Recipient> findForAccount(UUID ownerId) {
        return jdbc.sql(ACCOUNT_RECIPIENTS)
                .param("owner", ownerId)
                .query(RecipientDao::recipient)
                .list();
    }

    private static Recipient recipient(ResultSet rs, int row) throws SQLException {
        return new Recipient(rs.getString("email"), language(rs.getString("language")), rs.getBoolean("previous"));
    }

    Optional<Recipient> find(UUID ownerId) {
        return jdbc.sql("SELECT email, language FROM owner WHERE id = :ownerId AND email_verified_at IS NOT NULL")
                .param("ownerId", ownerId)
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
     * @param previous whether it is the address the owner had before changing it, which the notice then says
     */
    record Recipient(String email, Locale language, boolean previous) {

        Recipient(String email, Locale language) {
            this(email, language, false);
        }
    }

    record AttemptDetails(Recipient recipient, String label, String lockName, String timeZone) {
    }

    record LinkDetails(List<Recipient> recipients, String label, String lockName, String timeZone, Instant validFrom,
                       Instant validUntil) {
    }
}
