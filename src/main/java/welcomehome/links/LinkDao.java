package welcomehome.links;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * access_link has Row-Level Security: call it inside {@code OwnerTransactions} for the same owner. It
 * stores no token: a link's URL is recomputed from its id and token_version.
 */
@Repository
class LinkDao {

    private final JdbcClient jdbc;

    LinkDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The composite foreign key on (owner_id, lock_id) rejects another owner's lock. */
    InsertedLink insert(UUID ownerId, UUID lockId, String label, Instant validFrom, Instant validUntil) {
        return jdbc.sql("""
                        INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until)
                        VALUES (:ownerId, :lockId, :label, :validFrom, :validUntil)
                        RETURNING id, token_version
                        """)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .param("label", label)
                .param("validFrom", OffsetDateTime.ofInstant(validFrom, ZoneOffset.UTC))
                .param("validUntil", OffsetDateTime.ofInstant(validUntil, ZoneOffset.UTC))
                .query(InsertedLink.class)
                .single();
    }

    record InsertedLink(UUID id, int tokenVersion) {
    }
}
