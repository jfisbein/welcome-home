package welcomehome.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import welcomehome.config.Languages;

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

    UUID insert(String email, boolean isAdmin, Locale language) {
        return jdbc.sql("""
                        INSERT INTO owner (email, is_admin, language) VALUES (:email, :isAdmin, :language) RETURNING id
                        """)
                .param("email", email)
                .param("isAdmin", isAdmin)
                .param("language", language.toLanguageTag())
                .query(UUID.class)
                .single();
    }

    /** @return the owner's language, or empty if there is no such owner or the app no longer has texts for it */
    Optional<Locale> findLanguage(String email) {
        return jdbc.sql("SELECT language FROM owner WHERE email = :email")
                .param("email", email)
                .query(String.class)
                .optional()
                .flatMap(Languages::of);
    }

    void setLanguage(UUID ownerId, Locale language) {
        jdbc.sql("UPDATE owner SET language = :language WHERE id = :id")
                .param("language", language.toLanguageTag())
                .param("id", ownerId)
                .update();
    }

    record OwnerAccount(UUID id, String email, boolean isAdmin) {
    }
}
