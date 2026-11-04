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

    private final JdbcClient jdbc;

    LockDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** From owner, which has no Row-Level Security: without an owner context lock shows no rows. */
    List<UUID> allOwnerIds() {
        return jdbc.sql("SELECT id FROM owner ORDER BY created_at")
                .query((rs, row) -> rs.getObject("id", UUID.class))
                .list();
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

    List<OwnerLock> findAll(UUID ownerId) {
        return jdbc.sql("""
                        SELECT id, nuki_smartlock_id, name, time_zone, active, relocking_handled_elsewhere, device_type,
                               entrance_lock_id, guest_instructions, latitude, longitude
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
                        SELECT id, nuki_smartlock_id, name, time_zone, active, relocking_handled_elsewhere, device_type,
                               entrance_lock_id, guest_instructions, latitude, longitude
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
                           @Nullable Coordinates coordinates) {
        return jdbc.sql("""
                        UPDATE lock SET name = :name, time_zone = :timeZone,
                                        relocking_handled_elsewhere = :relockingHandledElsewhere,
                                        entrance_lock_id = :entranceLockId,
                                        guest_instructions = :guestInstructions,
                                        latitude = :latitude, longitude = :longitude
                        WHERE owner_id = :ownerId AND id = :lockId
                        """)
                .param("name", name)
                .param("timeZone", timeZone)
                .param("relockingHandledElsewhere", relockingHandledElsewhere)
                .param("entranceLockId", entranceLockId)
                .param("latitude", coordinates == null ? null : coordinates.latitude())
                .param("longitude", coordinates == null ? null : coordinates.longitude())
                .param("guestInstructions", guestInstructions)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .update() == 1;
    }

    /**
     * Inserts a lock found in Nuki, or marks an existing one as present again. An existing lock keeps the
     * name, time zone and position the owner chose, except that a position Nuki gives fills one it does not have.
     */
    void upsert(UUID ownerId, UUID connectionId, long nukiSmartlockId, DeviceType deviceType, String name,
                String timeZone, @Nullable Coordinates coordinates) {
        jdbc.sql("""
                        INSERT INTO lock (owner_id, connection_id, nuki_smartlock_id, device_type, name, time_zone,
                                          latitude, longitude)
                        VALUES (:ownerId, :connectionId, :nukiSmartlockId, :deviceType, :name, :timeZone,
                                :latitude, :longitude)
                        ON CONFLICT (owner_id, nuki_smartlock_id) DO UPDATE
                        SET connection_id = excluded.connection_id, device_type = excluded.device_type, active = true,
                            latitude = coalesce(lock.latitude, excluded.latitude),
                            longitude = coalesce(lock.longitude, excluded.longitude)
                        """)
                .param("ownerId", ownerId)
                .param("connectionId", connectionId)
                .param("nukiSmartlockId", nukiSmartlockId)
                .param("deviceType", deviceType.name())
                .param("name", name)
                .param("timeZone", timeZone)
                .param("latitude", coordinates == null ? null : coordinates.latitude())
                .param("longitude", coordinates == null ? null : coordinates.longitude())
                .update();
    }

    void deactivate(UUID ownerId, UUID lockId) {
        jdbc.sql("UPDATE lock SET active = false WHERE owner_id = :ownerId AND id = :lockId")
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .update();
    }
}
