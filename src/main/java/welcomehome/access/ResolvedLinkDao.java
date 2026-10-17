package welcomehome.access;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The guest's only way into the owners' data: resolve_link is SECURITY DEFINER, so it finds one link and its
 * lock without an owner context, and nothing else.
 */
@Repository
class ResolvedLinkDao {

    private final JdbcClient jdbc;

    ResolvedLinkDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<ResolvedLink> resolve(UUID linkId) {
        return jdbc.sql("SELECT * FROM resolve_link(:linkId)")
                .param("linkId", linkId)
                .query(ResolvedLink.class)
                .optional();
    }
}
