package welcomehome.auth;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The verification of an owner's email, in the owner table, which has no owner_id and no Row-Level Security. The
 * tokens of its links are stored only as hashes.
 */
@Repository
class EmailVerificationDao {

    private final JdbcClient jdbc;

    EmailVerificationDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean isVerified(UUID ownerId) {
        return jdbc.sql("SELECT email_verified_at IS NOT NULL FROM owner WHERE id = :id")
                .param("id", ownerId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /**
     * Stores a new link for an unverified owner, replacing any earlier one.
     *
     * @return the owner to send it to, or empty if there is no such unverified owner
     */
    Optional<Recipient> saveLink(UUID ownerId, byte[] tokenHash, Duration validity) {
        return jdbc.sql("""
                        UPDATE owner
                        SET email_verification_hash = :hash,
                            email_verification_expires_at = now() + make_interval(secs => :validitySeconds)
                        WHERE id = :id AND email_verified_at IS NULL
                        RETURNING email, language
                        """)
                .param("hash", tokenHash)
                .param("validitySeconds", validity.toSeconds())
                .param("id", ownerId)
                .query((rs, row) -> new Recipient(rs.getString("email"), rs.getString("language")))
                .optional();
    }

    /**
     * Tells whether an unverified owner's link still has more than this time to last, which is to say that it went
     * out recently.
     *
     * @return the answer, or empty if there is no such unverified owner
     */
    Optional<Boolean> linkLastsLongerThan(UUID ownerId, Duration time) {
        return jdbc.sql("""
                        SELECT coalesce(email_verification_expires_at > now() + make_interval(secs => :seconds), false)
                        FROM owner WHERE id = :id AND email_verified_at IS NULL
                        """)
                .param("seconds", time.toSeconds())
                .param("id", ownerId)
                .query(Boolean.class)
                .optional();
    }

    /** @return the address a link is for, if it can still be used */
    Optional<String> findPendingEmail(byte[] tokenHash) {
        return jdbc.sql("""
                        SELECT email FROM owner
                        WHERE email_verification_hash = :hash AND email_verification_expires_at > now()
                        """)
                .param("hash", tokenHash)
                .query(String.class)
                .optional();
    }

    /** @return the address a link is for, now verified, or empty if the link is unknown, used or expired */
    Optional<String> markVerified(byte[] tokenHash) {
        return jdbc.sql("""
                        UPDATE owner
                        SET email_verified_at = now(), email_verification_hash = NULL,
                            email_verification_expires_at = NULL
                        WHERE email_verification_hash = :hash AND email_verification_expires_at > now()
                        RETURNING email
                        """)
                .param("hash", tokenHash)
                .query(String.class)
                .optional();
    }

    record Recipient(String email, String language) {
    }
}
