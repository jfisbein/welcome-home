package welcomehome.locks;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.SimplePropertyRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
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

    List<OwnerLock> findAll(UUID ownerId) {
        return jdbc.sql("""
                        SELECT id, nuki_smartlock_id, name, time_zone, active, relocking_handled_elsewhere, device_type,
                               entrance_lock_id
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
                               entrance_lock_id
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
                           @Nullable UUID entranceLockId) {
        return jdbc.sql("""
                        UPDATE lock SET name = :name, time_zone = :timeZone,
                                        relocking_handled_elsewhere = :relockingHandledElsewhere,
                                        entrance_lock_id = :entranceLockId
                        WHERE owner_id = :ownerId AND id = :lockId
                        """)
                .param("name", name)
                .param("timeZone", timeZone)
                .param("relockingHandledElsewhere", relockingHandledElsewhere)
                .param("entranceLockId", entranceLockId)
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .update() == 1;
    }

    /**
     * Inserts a lock found in Nuki, or marks an existing one as present again. An existing lock keeps the
     * name and time zone the owner chose.
     */
    void upsert(UUID ownerId, UUID connectionId, long nukiSmartlockId, DeviceType deviceType, String name,
                String timeZone) {
        jdbc.sql("""
                        INSERT INTO lock (owner_id, connection_id, nuki_smartlock_id, device_type, name, time_zone)
                        VALUES (:ownerId, :connectionId, :nukiSmartlockId, :deviceType, :name, :timeZone)
                        ON CONFLICT (owner_id, nuki_smartlock_id) DO UPDATE
                        SET connection_id = excluded.connection_id, device_type = excluded.device_type, active = true
                        """)
                .param("ownerId", ownerId)
                .param("connectionId", connectionId)
                .param("nukiSmartlockId", nukiSmartlockId)
                .param("deviceType", deviceType.name())
                .param("name", name)
                .param("timeZone", timeZone)
                .update();
    }

    void deactivate(UUID ownerId, UUID lockId) {
        jdbc.sql("UPDATE lock SET active = false WHERE owner_id = :ownerId AND id = :lockId")
                .param("ownerId", ownerId)
                .param("lockId", lockId)
                .update();
    }
}
