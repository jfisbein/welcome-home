package welcomehome.audit;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import welcomehome.db.OwnerTransactions;

/**
 * An owner's latest guest attempts across all their locks, for the home page.
 */
@Service
public class RecentAttempts {

    private final JdbcClient jdbc;
    private final OwnerTransactions ownerTransactions;
    private final AttemptRows rows;

    RecentAttempts(JdbcClient jdbc, OwnerTransactions ownerTransactions, AttemptRows rows) {
        this.jdbc = jdbc;
        this.ownerTransactions = ownerTransactions;
        this.rows = rows;
    }

    /**
     * Finds the owner's latest attempts, newest first.
     *
     * @param ownerId the owner
     * @param limit   how many attempts at most
     * @param locale  the language of the outcomes and countries
     * @return the attempts, each in its lock's time zone
     */
    public List<AttemptRow> latest(UUID ownerId, int limit, Locale locale) {
        return ownerTransactions.execute(ownerId, () -> jdbc.sql("""
                        SELECT e.occurred_at, k.time_zone, e.lock_id, k.name AS lock_name, e.link_id,
                               l.label AS link_label, e.result, e.reason, host(e.ip) AS ip, e.country
                        FROM access_event e
                        JOIN lock k ON k.owner_id = e.owner_id AND k.id = e.lock_id
                        JOIN access_link l ON l.owner_id = e.owner_id AND l.id = e.link_id
                        WHERE e.owner_id = :ownerId
                        ORDER BY e.occurred_at DESC, e.id DESC
                        LIMIT :limit
                        """)
                .param("ownerId", ownerId)
                .param("limit", limit)
                .query((rs, row) -> rows.row(
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        ZoneId.of(rs.getString("time_zone")),
                        rs.getObject("lock_id", UUID.class),
                        rs.getString("lock_name"),
                        rs.getObject("link_id", UUID.class),
                        rs.getString("link_label"),
                        AccessResult.valueOf(rs.getString("result")),
                        rs.getString("reason"),
                        rs.getString("ip"),
                        rs.getString("country"),
                        locale))
                .list());
    }
}
