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
