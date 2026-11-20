package welcomehome.locks;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.SimplePropertyRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import welcomehome.nuki.Coordinates;
import welcomehome.nuki.DeviceType;

/**
 * lock has Row-Level Security: call it inside {@code OwnerTransactions} for the same owner. Every
 * statement also filters by owner_id, so it never depends on RLS alone.
 */
@Repository
class LockDao {

    /**
     * The check as the app names it: the strictest one is stored as REFUSE_WHEN_FAR with location_required, so that
     * a release that does not know it still reads the row (changeset 034). REFUSE_WHEN_FAR without it was a mode
     * of its own until changeset 043 made its locks CONFIRM_WHEN_FAR; a release before that may still store it, and
     * it is read as what those locks became. So is a value this release does not know, which a later one may store
     * since changeset 044 took the column's CHECK away: asking to confirm neither opens the door from anywhere
     * nor leaves a guest at it.
     */
    private static final String LOCATION_CHECK = """
            CASE WHEN location_check IN ('OFF', 'CONFIRM_WHEN_FAR') THEN location_check
                 WHEN location_check = 'REFUSE_WHEN_FAR' AND location_required THEN 'REQUIRE_NEAR'
                 ELSE 'CONFIRM_WHEN_FAR' END AS location_check""";

    /**
     * The device as this release knows it: a kind of device it does not know, which a later release may store
     * since changeset 044, is read as a smart lock, the kind every lock was before there were two, rather than
     * failing every page that lists the owner's locks.
     */
    private static final String DEVICE_TYPE = """
            CASE WHEN device_type IN ('SMART_LOCK', 'OPENER') THEN device_type ELSE 'SMART_LOCK' END AS device_type""";

    private final JdbcClient jdbc;

    LockDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the problems the lock was last emailed about, comma-separated, or {@code null} for none */
    @Nullable String findNotifiedProblems(UUID ownerId, UUID lockId) {
        return jdbc.sql("SELECT notified_problems FROM lock WHERE owner_id = :ownerId AND id = :lockId")
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .query((rs, row) -> rs.getString("notified_problems"))
                .optional()
                .orElse(null);
    }

    void saveNotifiedProblems(UUID ownerId, UUID lockId, @Nullable String problems) {
        jdbc.sql("UPDATE lock SET notified_problems = :problems WHERE owner_id = :ownerId AND id = :lockId")
                .param("problems", problems)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .update();
    }

    /**
     * What is kept of Nuki reporting a lock offline.
     *
     * @param reported  whether Nuki reported the lock offline when last asked
     * @param disproved whether an opening has succeeded since Nuki began to, and none has failed after it
     */
    record OfflineReport(boolean reported, boolean disproved) {
    }

    Optional<OfflineReport> findOfflineReport(UUID ownerId, UUID lockId) {
        return jdbc.sql("""
                        SELECT offline_since IS NOT NULL AS reported, offline_disproved_at IS NOT NULL AS disproved
                        FROM lock
                        WHERE owner_id = :ownerId AND id = :lockId
                        """)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .query((rs, row) -> new OfflineReport(rs.getBoolean("reported"), rs.getBoolean("disproved")))
                .optional();
    }

    /** Nuki began to report the lock offline: nothing that opened before tells against it. */
    void startOfflineReport(UUID ownerId, UUID lockId) {
        jdbc.sql("""
                        UPDATE lock SET offline_since = now(), offline_disproved_at = NULL
                        WHERE owner_id = :ownerId AND id = :lockId
                        """)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .update();
    }

    void endOfflineReport(UUID ownerId, UUID lockId) {
        jdbc.sql("""
                        UPDATE lock SET offline_since = NULL, offline_disproved_at = NULL
                        WHERE owner_id = :ownerId AND id = :lockId
                        """)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .update();
    }

    /** An opening succeeded: it disproves a report that Nuki was making, and says nothing without one. */
    void disproveOfflineReport(UUID ownerId, UUID lockId) {
        jdbc.sql("""
                        UPDATE lock SET offline_disproved_at = now()
                        WHERE owner_id = :ownerId AND id = :lockId AND offline_since IS NOT NULL
                        """)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .update();
    }

    void believeOfflineReport(UUID ownerId, UUID lockId) {
        jdbc.sql("""
                        UPDATE lock SET offline_disproved_at = NULL
                        WHERE owner_id = :ownerId AND id = :lockId AND offline_disproved_at IS NOT NULL
                        """)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .update();
    }

    List<OwnerLock> findAll(UUID ownerId) {
        return jdbc.sql("""
                        SELECT id, nuki_smartlock_id, name, time_zone, active, relocking_handled_elsewhere,
                               """ + DEVICE_TYPE + """
                               , entrance_lock_id, guest_instructions, latitude, longitude,
                               """ + LOCATION_CHECK + """
                               , location_radius_m
                        FROM lock
                        WHERE owner_id = :ownerId
                        ORDER BY active DESC, name
                        """)
                .param("ownerId", ownerId)
                .query(new SimplePropertyRowMapper<>(OwnerLock.class))
                .list();
    }

    Optional<OwnerLock> find(UUID ownerId, UUID lockId) {
        return jdbc.sql("""
                        SELECT id, nuki_smartlock_id, name, time_zone, active, relocking_handled_elsewhere,
                               """ + DEVICE_TYPE + """
                               , entrance_lock_id, guest_instructions, latitude, longitude,
                               """ + LOCATION_CHECK + """
                               , location_radius_m
                        FROM lock
                        WHERE owner_id = :ownerId AND id = :lockId
                        """)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .query(OwnerLock.class)
                .optional();
    }

    /** @return whether the owner has that lock */
    boolean updateSettings(UUID ownerId, UUID lockId, String name, String timeZone, boolean relockingHandledElsewhere,
                           @Nullable UUID entranceLockId, @Nullable String guestInstructions,
                           @Nullable Coordinates coordinates, LocationCheck locationCheck, int locationRadiusM) {
        return jdbc.sql("""
                        UPDATE lock SET name = :name, time_zone = :timeZone,
                                        relocking_handled_elsewhere = :relockingHandledElsewhere,
                                        entrance_lock_id = :entranceLockId,
                                        guest_instructions = :guestInstructions,
                                        latitude = :latitude, longitude = :longitude,
                                        location_check = :locationCheck, location_required = :locationRequired,
                                        location_radius_m = :locationRadiusM
                        WHERE owner_id = :ownerId AND id = :lockId
                        """)
                .param("name", name)
                .param("timeZone", timeZone)
                .param("relockingHandledElsewhere", relockingHandledElsewhere)
                .param("entranceLockId", entranceLockId)
                .param("latitude", coordinates == null ? null : coordinates.latitude())
                .param("longitude", coordinates == null ? null : coordinates.longitude())
                .param("locationCheck", locationCheck == LocationCheck.REQUIRE_NEAR ? "REFUSE_WHEN_FAR"
                        : locationCheck.name())
                .param("locationRequired", locationCheck == LocationCheck.REQUIRE_NEAR)
                .param("locationRadiusM", locationRadiusM)
                .param("guestInstructions", guestInstructions)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .update() == 1;
    }

    /**
     * Inserts a lock found in Nuki, or marks an existing one as present again. An existing lock keeps the
     * name, time zone and position the owner chose, except that a position Nuki gives fills one it does not have.
     * A new lock that comes with a position checks where its guests are from the start, asking one who seems far
     * to confirm, so that no owner has to know of the check for a slide at home not to open their door; an existing
     * lock keeps what its owner chose.
     */
    void upsert(UUID ownerId, long nukiSmartlockId, DeviceType deviceType, String name,
                String timeZone, @Nullable Coordinates coordinates) {
        jdbc.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, device_type, name, time_zone,
                                          latitude, longitude, location_check)
                        VALUES (:ownerId, :nukiSmartlockId, :deviceType, :name, :timeZone,
                                :latitude, :longitude, :locationCheck)
                        ON CONFLICT (owner_id, nuki_smartlock_id) DO UPDATE
                        SET device_type = excluded.device_type, active = true,
                            latitude = coalesce(lock.latitude, excluded.latitude),
                            longitude = coalesce(lock.longitude, excluded.longitude)
                        """)
                .param("ownerId", ownerId)
                .param("nukiSmartlockId", nukiSmartlockId)
                .param("deviceType", deviceType.name())
                .param("name", name)
                .param("timeZone", timeZone)
                .param("latitude", coordinates == null ? null : coordinates.latitude())
                .param("longitude", coordinates == null ? null : coordinates.longitude())
                .param("locationCheck", (coordinates == null ? LocationCheck.OFF : LocationCheck.CONFIRM_WHEN_FAR).name())
                .update();
    }

    void deactivate(UUID ownerId, UUID lockId) {
        jdbc.sql("UPDATE lock SET active = false WHERE owner_id = :ownerId AND id = :lockId")
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .update();
    }
}
