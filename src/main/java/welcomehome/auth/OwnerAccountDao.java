package welcomehome.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
     * @return whether there was such an owner
     */
    boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM owner WHERE id = :id").param("id", id).update() == 1;
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
     * @return the owner's language
     */
    String changeEmail(UUID id, String email) {
        var language = jdbc.sql("""
                        UPDATE owner SET email = :email, email_verified_at = NULL, email_verification_hash = NULL,
                                         email_verification_expires_at = NULL
                        WHERE id = :id
                        RETURNING language
                        """)
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
