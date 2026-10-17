package welcomehome.admin;

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
}
