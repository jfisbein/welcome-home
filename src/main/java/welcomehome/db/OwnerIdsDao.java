package welcomehome.db;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the ids of every owner, for {@link Owners}. */
@Repository
class OwnerIdsDao {

    private final JdbcClient jdbc;

    OwnerIdsDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** From owner, which has no Row-Level Security: without an owner context the other tables show no rows. */
    List<UUID> findAll() {
        return jdbc.sql("SELECT id FROM owner ORDER BY created_at")
                .query((rs, row) -> rs.getObject("id", UUID.class))
                .list();
    }
}
