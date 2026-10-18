package welcomehome.notifications;

import java.util.UUID;

import org.springframework.jdbc.core.SimplePropertyRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Owners' notification preferences, under Row-Level Security: call it inside an owner transaction. */
@Repository
class NotificationPreferenceDao {

    private final JdbcClient jdbc;

    NotificationPreferenceDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    NotificationPreferences find(UUID ownerId) {
        return jdbc.sql("""
                        SELECT opened, failed, denied, disconnected FROM notification_preference
                        WHERE owner_id = :ownerId
                        """)
                .param("ownerId", ownerId)
                .query(new SimplePropertyRowMapper<>(NotificationPreferences.class))
                .optional()
                .orElse(NotificationPreferences.ALL);
    }

    void save(UUID ownerId, NotificationPreferences preferences) {
        jdbc.sql("""
                        INSERT INTO notification_preference (owner_id, opened, failed, denied, disconnected)
                        VALUES (:ownerId, :opened, :failed, :denied, :disconnected)
                        ON CONFLICT (owner_id) DO UPDATE SET opened = excluded.opened, failed = excluded.failed,
                            denied = excluded.denied, disconnected = excluded.disconnected
                        """)
                .param("ownerId", ownerId)
                .param("opened", preferences.opened())
                .param("failed", preferences.failed())
                .param("denied", preferences.denied())
                .param("disconnected", preferences.disconnected())
                .update();
    }
}
