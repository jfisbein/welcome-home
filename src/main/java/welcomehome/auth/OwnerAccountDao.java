package welcomehome.auth;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.SimplePropertyRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner table has no owner_id and no Row-Level Security: authentication reads it by email.
 */
@Repository
class OwnerAccountDao {

    private final JdbcClient jdbc;

    OwnerAccountDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<OwnerAccount> findByEmail(String email) {
        return jdbc.sql("SELECT id, email, is_admin FROM owner WHERE email = :email")
                .param("email", email)
                .query(OwnerAccount.class)
                .optional();
    }

    Optional<OwnerAccount> findById(UUID id) {
        return jdbc.sql("SELECT id, email, is_admin FROM owner WHERE id = :id")
                .param("id", id)
                .query(OwnerAccount.class)
                .optional();
    }

    /**
     * Deletes an owner and everything of theirs: the owner row's foreign keys cascade to their passkeys, Nuki
     * connection, locks, links, history and recovery links.
     *
     * Never the only admin, so that an admin is always left: the admins' rows are locked first, as two admins
     * deleting each other at once, or each closing their own account, would each see the other as still there.
     *
     * @return whether there was such an owner
     * @throws LastAdminException if the owner is the only admin
     */
    @Transactional
    boolean delete(UUID id) {
        jdbc.sql("SELECT id FROM owner WHERE is_admin ORDER BY id FOR UPDATE")
                .query((rs, row) -> rs.getString("id"))
                .list();
        if (isOnlyAdmin(id)) {
            throw new LastAdminException();
        }
        return jdbc.sql("DELETE FROM owner WHERE id = :id").param("id", id).update() == 1;
    }

    boolean adminExists() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM owner WHERE is_admin)")
                .query(Boolean.class)
                .single();
    }

    /** Only what the admin panel shows. */
    List<OwnerSummary> findAll() {
        return jdbc.sql("SELECT email, created_at::date AS registered_on, is_admin AS admin FROM owner ORDER BY created_at")
                .query(new SimplePropertyRowMapper<>(OwnerSummary.class))
                .list();
    }

    /**
     * Through make_admin, as the app may not update is_admin: it can give the role and never take it away.
     *
     * @return whether the owner became an admin, which one who was already, or nobody, does not
     */
    boolean makeAdmin(UUID id) {
        return jdbc.sql("SELECT make_admin(:id)").param("id", id).query(Boolean.class).single();
    }

    /** @return whether the owner is an admin and no other owner is */
    boolean isOnlyAdmin(UUID id) {
        return jdbc.sql("""
                        SELECT is_admin AND NOT EXISTS (SELECT 1 FROM owner o WHERE o.is_admin AND o.id <> owner.id)
                        FROM owner WHERE id = :id
                        """)
                .param("id", id)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /**
     * Gives the owner another email, unverified, and the passkey store the same name for them. owner first: the
     * trigger on user_entities finds its owner by that name.
     *
     * @param keepPrevious whether the address they had was verified: it is then kept for {@code previousKept}, to get
     *                     the notices about the account too; otherwise what was kept stays as it was
     * @return the owner's language
     */
    String changeEmail(UUID id, String email, boolean keepPrevious, Duration previousKept) {
        var language = jdbc.sql("""
                        UPDATE owner SET previous_email = CASE WHEN :keepPrevious THEN email ELSE previous_email END,
                                         previous_email_until = CASE WHEN :keepPrevious
                                             THEN now() + make_interval(secs => :keptSeconds)
                                             ELSE previous_email_until END,
                                         email = :email, email_verified_at = NULL, email_verification_hash = NULL,
                                         email_verification_expires_at = NULL
                        WHERE id = :id
                        RETURNING language
                        """)
                .param("keepPrevious", keepPrevious)
                .param("keptSeconds", previousKept.toSeconds())
                .param("email", email)
                .param("id", id)
                .query(String.class)
                .single();
        jdbc.sql("UPDATE user_entities SET name = :email, display_name = :email WHERE owner_id = :id")
                .param("email", email)
                .param("id", id)
                .update();
        return language;
    }

    /**
     * Forgets the addresses owners had before changing them, once they no longer get notices.
     *
     * @return how many were forgotten
     */
    int forgetPreviousEmails() {
        return jdbc.sql("""
                        UPDATE owner SET previous_email = NULL, previous_email_until = NULL
                        WHERE previous_email_until < now()
                        """)
                .update();
    }

    UUID insert(String email, boolean isAdmin, Locale language) {
        return jdbc.sql("""
                        INSERT INTO owner (email, is_admin, language, email_verified_at)
                        VALUES (:email, :isAdmin, :language, NULL)
                        RETURNING id
                        """)
                .param("email", email)
                .param("isAdmin", isAdmin)
                .param("language", language.toLanguageTag())
                .query(UUID.class)
                .single();
    }

    record OwnerAccount(UUID id, String email, boolean isAdmin) {
    }
}
