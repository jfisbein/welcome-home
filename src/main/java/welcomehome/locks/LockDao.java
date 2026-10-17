package welcomehome.locks;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
                        SELECT id, nuki_smartlock_id, name, time_zone, active
                        FROM lock
                        WHERE owner_id = :ownerId
                        ORDER BY active DESC, name
                        """)
                .param("ownerId", ownerId)
                .query(OwnerLock.class)
                .list();
    }

    /**
     * Inserts a lock found in Nuki, or marks an existing one as present again. An existing lock keeps the
     * name and time zone the owner chose.
     */
    void upsert(UUID ownerId, UUID connectionId, long nukiSmartlockId, String name, String timeZone) {
        jdbc.sql("""
                        INSERT INTO lock (owner_id, connection_id, nuki_smartlock_id, name, time_zone)
                        VALUES (:ownerId, :connectionId, :nukiSmartlockId, :name, :timeZone)
                        ON CONFLICT (owner_id, nuki_smartlock_id) DO UPDATE
                        SET connection_id = excluded.connection_id, active = true
                        """)
                .param("ownerId", ownerId)
                .param("connectionId", connectionId)
                .param("nukiSmartlockId", nukiSmartlockId)
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
