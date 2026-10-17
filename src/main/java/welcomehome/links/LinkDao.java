package welcomehome.links;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
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

    List<StoredLink> findByLock(UUID ownerId, UUID lockId) {
        return jdbc.sql("""
                        SELECT id, label, token_version, valid_from, valid_until, revoked_at
                        FROM access_link
                        WHERE owner_id = :ownerId AND lock_id = :lockId
                        ORDER BY valid_from DESC
                        """)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .query(StoredLink.class)
                .list();
    }

    /** Revokes the link now, or keeps the first revocation if it already was revoked. */
    Optional<RevokedLink> revoke(UUID ownerId, UUID linkId) {
        return jdbc.sql("""
                        UPDATE access_link SET revoked_at = coalesce(revoked_at, now())
                        WHERE owner_id = :ownerId AND id = :linkId
                        RETURNING lock_id, label
                        """)
                .param("ownerId", ownerId)
                .param("linkId", linkId)
                .query(RevokedLink.class)
                .optional();
    }

    record InsertedLink(UUID id, int tokenVersion) {
    }

    record RevokedLink(UUID lockId, String label) {
    }

    record StoredLink(UUID id, String label, int tokenVersion, OffsetDateTime validFrom, OffsetDateTime validUntil,
                      @Nullable OffsetDateTime revokedAt) {
    }
}
