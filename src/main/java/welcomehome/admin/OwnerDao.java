package welcomehome.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The owner table has no owner_id and no Row-Level Security: the admin module may read it whole.
 */
@Repository
class OwnerDao {

    private final JdbcClient jdbc;

    OwnerDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean adminExists() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM owner WHERE is_admin)")
                .query(Boolean.class)
                .single();
    }

    Optional<UUID> findIdByEmail(String email) {
        return jdbc.sql("SELECT id FROM owner WHERE email = :email")
                .param("email", email)
                .query(UUID.class)
                .optional();
    }

    /** Only what the admin panel shows: never anything from the owners' locks or links. */
    List<OwnerSummary> findAll() {
        return jdbc.sql("SELECT email, created_at::date AS registered_on FROM owner ORDER BY created_at")
                .query(OwnerSummary.class)
                .list();
    }

    record OwnerSummary(String email, LocalDate registeredOn) {
    }
}
