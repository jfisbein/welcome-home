package welcomehome.links;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.SimplePropertyRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * access_link and access_link_schedule have Row-Level Security: call it inside {@code OwnerTransactions} for the same owner. It
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

    /**
     * Deletes the owner's links that ended before the cutoff, by expiring or being cancelled, and have no attempt since.
     *
     * @return how many were deleted
     */
    int deleteEndedBefore(UUID ownerId, Instant cutoff) {
        return jdbc.sql("""
                        DELETE FROM access_link l
                        WHERE l.owner_id = :ownerId AND least(l.valid_until, l.revoked_at) < :cutoff
                          AND NOT EXISTS (SELECT 1 FROM access_event e
                                          WHERE e.owner_id = l.owner_id AND e.link_id = l.id AND e.occurred_at >= :cutoff)
                        """)
                .param("ownerId", ownerId)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }

    /** @return the link's encrypted PIN, or empty if it has none or the owner has no such link */
    Optional<byte[]> findPin(UUID ownerId, UUID linkId) {
        return jdbc.sql("SELECT pin_encrypted FROM access_link WHERE owner_id = :ownerId AND id = :linkId "
                        + "AND pin_encrypted IS NOT NULL")
                .param("ownerId", ownerId)
                .param("linkId", linkId)
                .query((rs, row) -> rs.getBytes("pin_encrypted"))
                .optional();
    }

    /** Sets the link's PIN. */
    void setPin(UUID ownerId, UUID linkId, byte[] pinEncrypted) {
        jdbc.sql("UPDATE access_link SET pin_encrypted = :pin WHERE owner_id = :ownerId AND id = :linkId")
                .param("pin", pinEncrypted)
                .param("ownerId", ownerId)
                .param("linkId", linkId)
                .update();
    }

    /** Takes the link's PIN off. */
    void clearPin(UUID ownerId, UUID linkId) {
        jdbc.sql("UPDATE access_link SET pin_encrypted = NULL WHERE owner_id = :ownerId AND id = :linkId")
                .param("ownerId", ownerId)
                .param("linkId", linkId)
                .update();
    }

    /** The owner's links that have a PIN, with it as it is stored. */
    Map<UUID, byte[]> findPins(UUID ownerId) {
        var pins = new HashMap<UUID, byte[]>();
        jdbc.sql("SELECT id, pin_encrypted FROM access_link WHERE owner_id = :ownerId AND pin_encrypted IS NOT NULL")
                .param("ownerId", ownerId)
                .query(rs -> {
                    pins.put(rs.getObject("id", UUID.class), rs.getBytes("pin_encrypted"));
                });
        return pins;
    }

    /** From owner, which has no Row-Level Security: without an owner context access_link shows no rows. */
    List<UUID> allOwnerIds() {
        return jdbc.sql("SELECT id FROM owner ORDER BY created_at")
                .query((rs, row) -> rs.getObject("id", UUID.class))
                .list();
    }

    List<StoredLink> findByLock(UUID ownerId, UUID lockId) {
        return jdbc.sql("""
                        SELECT id, label, token_version, valid_from, valid_until, revoked_at, pin_encrypted IS NOT NULL AS has_pin
                        FROM access_link
                        WHERE owner_id = :ownerId AND lock_id = :lockId
                        ORDER BY valid_from DESC
                        """)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .query(new SimplePropertyRowMapper<>(StoredLink.class))
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

    /**
     * Deletes a link that can no longer open, as it expired or was revoked; its schedule and its attempts go with it.
     *
     * @return its lock and label, or empty if the owner has no such link or it can still open
     */
    Optional<DeletedLink> deleteEnded(UUID ownerId, UUID linkId) {
        return jdbc.sql("""
                        DELETE FROM access_link
                        WHERE owner_id = :ownerId AND id = :linkId AND (revoked_at IS NOT NULL OR valid_until <= now())
                        RETURNING lock_id, label
                        """)
                .param("ownerId", ownerId)
                .param("linkId", linkId)
                .query(DeletedLink.class)
                .optional();
    }

    boolean exists(UUID ownerId, UUID linkId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM access_link WHERE owner_id = :ownerId AND id = :linkId)")
                .param("ownerId", ownerId)
                .param("linkId", linkId)
                .query(Boolean.class)
                .single();
    }

    /** Invalidates every earlier URL of a link that is not revoked. */
    Optional<RegeneratedLink> incrementTokenVersion(UUID ownerId, UUID linkId) {
        return jdbc.sql("""
                        UPDATE access_link SET token_version = token_version + 1
                        WHERE owner_id = :ownerId AND id = :linkId AND revoked_at IS NULL
                        RETURNING id, token_version, lock_id, label
                        """)
                .param("ownerId", ownerId)
                .param("linkId", linkId)
                .query(RegeneratedLink.class)
                .optional();
    }

    /** A link that is not revoked, with what editing it needs from its lock. */
    Optional<EditableLink> findEditable(UUID ownerId, UUID linkId) {
        return jdbc.sql("""
                        SELECT l.id, l.lock_id, k.name AS lock_name, k.time_zone, l.label, l.valid_from, l.valid_until
                        FROM access_link l JOIN lock k ON k.owner_id = l.owner_id AND k.id = l.lock_id
                        WHERE l.owner_id = :ownerId AND l.id = :linkId AND l.revoked_at IS NULL
                        """)
                .param("ownerId", ownerId)
                .param("linkId", linkId)
                .query(EditableLink.class)
                .optional();
    }

    /** @return the link's lock, or empty if the owner has no such link or it is revoked */
    Optional<UUID> update(UUID ownerId, UUID linkId, String label, Instant validFrom, Instant validUntil) {
        return jdbc.sql("""
                        UPDATE access_link SET label = :label, valid_from = :validFrom, valid_until = :validUntil
                        WHERE owner_id = :ownerId AND id = :linkId AND revoked_at IS NULL
                        RETURNING lock_id
                        """)
                .param("label", label)
                .param("validFrom", OffsetDateTime.ofInstant(validFrom, ZoneOffset.UTC))
                .param("validUntil", OffsetDateTime.ofInstant(validUntil, ZoneOffset.UTC))
                .param("ownerId", ownerId)
                .param("linkId", linkId)
                .query(UUID.class)
                .optional();
    }

    /** The composite foreign key on (owner_id, link_id) rejects another owner's link. */
    void replaceSchedule(UUID ownerId, UUID linkId, LinkSchedule schedule) {
        jdbc.sql("DELETE FROM access_link_schedule WHERE owner_id = :ownerId AND link_id = :linkId")
                .param("ownerId", ownerId)
                .param("linkId", linkId)
                .update();
        for (var hours : schedule.days()) {
            jdbc.sql("""
                            INSERT INTO access_link_schedule (owner_id, link_id, day_of_week, opens_at, closes_at)
                            VALUES (:ownerId, :linkId, :day, :opens, :closes)
                            """)
                    .param("ownerId", ownerId)
                    .param("linkId", linkId)
                    .param("day", hours.day().getValue())
                    .param("opens", hours.opens())
                    .param("closes", hours.closes())
                    .update();
        }
    }

    LinkSchedule findSchedule(UUID ownerId, UUID linkId) {
        return new LinkSchedule(jdbc.sql("""
                        SELECT day_of_week, opens_at, closes_at FROM access_link_schedule
                        WHERE owner_id = :ownerId AND link_id = :linkId
                        """)
                .param("ownerId", ownerId)
                .param("linkId", linkId)
                .query((rs, row) -> hours(rs))
                .list());
    }

    /** The schedules of a lock's links that have one. */
    Map<UUID, LinkSchedule> findSchedulesByLock(UUID ownerId, UUID lockId) {
        var hoursByLink = new HashMap<UUID, List<DayHours>>();
        jdbc.sql("""
                        SELECT s.link_id, s.day_of_week, s.opens_at, s.closes_at
                        FROM access_link_schedule s
                        JOIN access_link l ON l.owner_id = s.owner_id AND l.id = s.link_id
                        WHERE s.owner_id = :ownerId AND l.lock_id = :lockId
                        """)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .query(rs -> {
                    hoursByLink.computeIfAbsent(rs.getObject("link_id", UUID.class), link -> new ArrayList<>())
                            .add(hours(rs));
                });
        var schedules = new HashMap<UUID, LinkSchedule>();
        hoursByLink.forEach((link, hours) -> schedules.put(link, new LinkSchedule(hours)));
        return schedules;
    }

    private static DayHours hours(ResultSet rs) throws SQLException {
        return new DayHours(DayOfWeek.of(rs.getInt("day_of_week")), rs.getObject("opens_at", LocalTime.class),
                rs.getObject("closes_at", LocalTime.class));
    }

    record InsertedLink(UUID id, int tokenVersion) {
    }

    record EditableLink(UUID id, UUID lockId, String lockName, String timeZone, String label, OffsetDateTime validFrom,
                        OffsetDateTime validUntil) {
    }

    record RegeneratedLink(UUID id, int tokenVersion, UUID lockId, String label) {
    }

    record RevokedLink(UUID lockId, String label) {
    }

    record DeletedLink(UUID lockId, String label) {
    }

    record StoredLink(UUID id, String label, int tokenVersion, OffsetDateTime validFrom, OffsetDateTime validUntil,
                      @Nullable OffsetDateTime revokedAt, boolean hasPin) {
    }
}
