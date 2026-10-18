package welcomehome.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.SimplePropertyRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Deletes an owner and everything of theirs, in one transaction. The owner row's foreign keys cascade to
     * their Nuki connection, locks, links, history and recovery links; their passkeys, in Spring Security's
     * tables keyed by email, are deleted here.
     *
     * @return whether there was such an owner
     */
    @Transactional
    boolean delete(String email) {
        jdbc.sql("""
                        DELETE FROM user_credentials c USING user_entities u
                        WHERE u.id = c.user_entity_user_id AND u.name = :email
                        """)
                .param("email", email)
                .update();
        jdbc.sql("DELETE FROM user_entities WHERE name = :email").param("email", email).update();
        return jdbc.sql("DELETE FROM owner WHERE email = :email").param("email", email).update() == 1;
    }

    /** Only what the admin panel shows: never anything from the owners' locks or links. */
    List<OwnerSummary> findAll() {
        return jdbc.sql("SELECT email, created_at::date AS registered_on FROM owner ORDER BY created_at")
                .query(new SimplePropertyRowMapper<>(OwnerSummary.class))
                .list();
    }

    record OwnerSummary(String email, LocalDate registeredOn) {
    }
}
