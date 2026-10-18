package welcomehome.auth;

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

    UUID insert(String email, boolean isAdmin) {
        return jdbc.sql("INSERT INTO owner (email, is_admin) VALUES (:email, :isAdmin) RETURNING id")
                .param("email", email)
                .param("isAdmin", isAdmin)
                .query(UUID.class)
                .single();
    }

    record OwnerAccount(UUID id, String email, boolean isAdmin) {
    }
}
