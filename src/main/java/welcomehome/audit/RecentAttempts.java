package welcomehome.audit;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
    private final OutcomeTexts outcomes;

    RecentAttempts(JdbcClient jdbc, OwnerTransactions ownerTransactions, OutcomeTexts outcomes) {
        this.jdbc = jdbc;
        this.ownerTransactions = ownerTransactions;
        this.outcomes = outcomes;
    }

    /**
     * Finds the owner's latest attempts, newest first.
     *
     * @param ownerId the owner
     * @param limit   how many attempts at most
     * @param locale  the language of the outcomes
     * @return the attempts, each in its lock's time zone
     */
    public List<Attempt> latest(UUID ownerId, int limit, Locale locale) {
        return ownerTransactions.execute(ownerId, () -> jdbc.sql("""
                        SELECT e.occurred_at, k.time_zone, e.lock_id, k.name AS lock_name, e.link_id,
                               l.label AS link_label, e.result, e.reason
                        FROM access_event e
                        JOIN lock k ON k.owner_id = e.owner_id AND k.id = e.lock_id
                        JOIN access_link l ON l.owner_id = e.owner_id AND l.id = e.link_id
                        WHERE e.owner_id = :ownerId
                        ORDER BY e.occurred_at DESC, e.id DESC
                        LIMIT :limit
                        """)
                .param("ownerId", ownerId)
                .param("limit", limit)
                .query((rs, row) -> new Attempt(
                        rs.getObject("occurred_at", OffsetDateTime.class)
                                .atZoneSameInstant(ZoneId.of(rs.getString("time_zone"))),
                        rs.getObject("lock_id", UUID.class),
                        rs.getString("lock_name"),
                        rs.getObject("link_id", UUID.class),
                        rs.getString("link_label"),
                        AccessResult.valueOf(rs.getString("result")),
                        outcomes.describe(rs.getString("reason"), locale)))
                .list());
    }

    /**
     * A guest's attempt as the home page shows it.
     *
     * @param occurredAt when, in the lock's time zone
     * @param lockId     the lock
     * @param lockName   the lock's name
     * @param linkId     the link the guest used
     * @param linkLabel  the link's label
     * @param result     whether the door opened, the app refused, or the lock or Nuki failed
     * @param outcome    what happened, in words
     */
    public record Attempt(ZonedDateTime occurredAt, UUID lockId, String lockName, UUID linkId, String linkLabel,
                          AccessResult result, String outcome) {
    }
}
