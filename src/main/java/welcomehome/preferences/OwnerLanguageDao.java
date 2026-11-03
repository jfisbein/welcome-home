package welcomehome.preferences;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import welcomehome.config.Languages;

/**
 * An owner's language, in the owner table, which has no owner_id and no Row-Level Security: each page reads it by
 * the signed-in owner's email.
 */
@Repository
class OwnerLanguageDao {

    private final JdbcClient jdbc;

    OwnerLanguageDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
}
