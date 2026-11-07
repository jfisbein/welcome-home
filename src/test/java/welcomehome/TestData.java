package welcomehome;

import java.time.DayOfWeek;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The rows tests start from, written as the schema owner, so that a change to a table means a change here rather
 * than in every test. Times are SQL expressions, such as {@code now() - interval '1 day'}, as tests read best with
 * them. What a test checks about the SQL itself, such as a constraint or Row-Level Security, stays in that test.
 */
public final class TestData {

    /** The time zone of a lock unless a test says otherwise. */
    public static final String MADRID = "Europe/Madrid";
    /** An access link valid from an hour ago to a day from now. */
    public static final String AN_HOUR_AGO = "now() - interval '1 hour'";
    public static final String IN_A_DAY = "now() + interval '1 day'";

    private final JdbcClient db;

    /**
     * @param migrator a client connected as the schema owner, as {@link TestDatabaseConfiguration#asMigrator} gives
     */
    public TestData(JdbcClient migrator) {
        this.db = migrator;
    }

    /** Deletes every owner, and with them everything of theirs. */
    public void deleteOwners() {
        db.sql("DELETE FROM owner").update();
    }

    public UUID owner(String email) {
        return owner(email, false);
    }

    public UUID admin(String email) {
        return owner(email, true);
    }

    public UUID owner(String email, boolean admin) {
        return db.sql("INSERT INTO owner (email, is_admin) VALUES (?, ?) RETURNING id")
                .params(email, admin).query(UUID.class).single();
    }

    /** An owner whose pages and emails are in this language. */
    public UUID owner(String email, boolean admin, String language) {
        return db.sql("INSERT INTO owner (email, is_admin, language) VALUES (?, ?, ?) RETURNING id")
                .params(email, admin, language).query(UUID.class).single();
    }

    /** A smart lock in Madrid. */
    public UUID lock(UUID owner, long nukiSmartlockId, String name) {
        return lock(owner, nukiSmartlockId, name, MADRID);
    }

    public UUID lock(UUID owner, long nukiSmartlockId, String name, String timeZone) {
        return device(owner, nukiSmartlockId, name, timeZone, "SMART_LOCK");
    }

    public UUID opener(UUID owner, long nukiSmartlockId, String name) {
        return opener(owner, nukiSmartlockId, name, MADRID);
    }

    public UUID opener(UUID owner, long nukiSmartlockId, String name, String timeZone) {
        return device(owner, nukiSmartlockId, name, timeZone, "OPENER");
    }

    private UUID device(UUID owner, long nukiSmartlockId, String name, String timeZone, String deviceType) {
        return db.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone, device_type)
                        VALUES (?, ?, ?, ?, ?) RETURNING id
                        """)
                .params(owner, nukiSmartlockId, name, timeZone, deviceType).query(UUID.class).single();
    }

    /**
     * An Opener at the entrance of the lock's building, in the lock's time zone.
     *
     * @return the Opener
     */
    public UUID entrance(UUID lock, long nukiSmartlockId, String name) {
        var opener = db.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone, device_type)
                        SELECT owner_id, ?, ?, time_zone, 'OPENER' FROM lock WHERE id = ? RETURNING id
                        """)
                .params(nukiSmartlockId, name, lock).query(UUID.class).single();
        db.sql("UPDATE lock SET entrance_lock_id = ? WHERE id = ?").params(opener, lock).update();
        return opener;
    }

    /** What the owner tells the lock's guests on its links' page. */
    public void guestInstructions(UUID lock, String instructions) {
        db.sql("UPDATE lock SET guest_instructions = ? WHERE id = ?").params(instructions, lock).update();
    }

    /** A link valid from an hour ago to a day from now. */
    public UUID link(UUID lock, String label) {
        return link(lock, label, AN_HOUR_AGO, IN_A_DAY);
    }

    public UUID link(UUID lock, String label, String validFrom, String validUntil) {
        return link(lock, label, validFrom, validUntil, null);
    }

    /** @param revokedAt when it was revoked, or {@code null} for a link that was not */
    public UUID link(UUID lock, String label, String validFrom, String validUntil, @Nullable String revokedAt) {
        return db.sql("INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until, revoked_at) "
                        + "SELECT owner_id, id, ?, " + validFrom + ", " + validUntil + ", "
                        + (revokedAt == null ? "NULL" : revokedAt) + " FROM lock WHERE id = ? RETURNING id")
                .params(label, lock).query(UUID.class).single();
    }

    /** Gives a link the PIN that {@code CryptoService.encryptLinkPin} encrypted. */
    public void pin(UUID link, byte[] encrypted) {
        db.sql("UPDATE access_link SET pin_encrypted = ? WHERE id = ?").params(encrypted, link).update();
    }

    /** The hours at which a link opens on a day, local to its lock: 00:00 to 00:00 is the whole day. */
    public void schedule(UUID link, DayOfWeek day, String opens, String closes) {
        db.sql("""
                        INSERT INTO access_link_schedule (owner_id, link_id, day_of_week, opens_at, closes_at)
                        SELECT owner_id, id, ?, ?::time, ?::time FROM access_link WHERE id = ?
                        """)
                .params(day.getValue(), opens, closes, link).update();
    }

    /**
     * A guest's attempt with a link, at its lock.
     *
     * @param occurredAt when, as an SQL expression
     * @param result     {@code OK}, {@code DENIED} or {@code ERROR}
     * @param reason     the outcome's code, such as {@code opened}
     * @param ip         the guest's IP address, or {@code null}
     */
    public void attempt(UUID link, String occurredAt, String result, String reason, @Nullable String ip) {
        db.sql("INSERT INTO access_event (owner_id, link_id, lock_id, occurred_at, result, reason, ip) "
                        + "SELECT owner_id, id, lock_id, " + occurredAt + ", ?, ?, CAST(? AS inet) "
                        + "FROM access_link WHERE id = ?")
                .params(result, reason, ip, link).update();
    }

    /**
     * Where a lock is, and how it checks where its guests are.
     *
     * @param check {@code OFF}, {@code CONFIRM_WHEN_FAR} or {@code REFUSE_WHEN_FAR}
     */
    public void locationCheck(UUID lock, double latitude, double longitude, String check, int radiusM) {
        db.sql("UPDATE lock SET latitude = ?, longitude = ?, location_check = ?, location_radius_m = ? WHERE id = ?")
                .params(latitude, longitude, check, radiusM, lock).update();
    }

        /** A connection with a pasted API token, already encrypted for its owner. */
    public void apiToken(UUID owner, byte[] accessTokenEncrypted) {
        apiToken(owner, accessTokenEncrypted, "OK");
    }

    public void apiToken(UUID owner, byte[] accessTokenEncrypted, String status) {
        db.sql("INSERT INTO nuki_connection (owner_id, access_token_encrypted, status) VALUES (?, ?, ?)")
                .params(owner, accessTokenEncrypted, status).update();
    }

    /**
     * A connection authorized with OAuth2, its tokens already encrypted for their owner.
     *
     * @param expiresIn when the access token expires, from now, as an SQL interval such as {@code '1 hour'}
     */
    public void authorization(UUID owner, byte[] accessTokenEncrypted, byte[] refreshTokenEncrypted, String expiresIn,
                              String status) {
        db.sql("""
                        INSERT INTO nuki_connection (owner_id, credential, access_token_encrypted,
                                                     refresh_token_encrypted, access_token_expires_at, status)
                        VALUES (?, 'OAUTH2', ?, ?, now() + ?::interval, ?)
                        """)
                .params(owner, accessTokenEncrypted, refreshTokenEncrypted, expiresIn, status).update();
    }

    /** A user of Spring Security's passkey store, which knows its owner by email. */
    public void passkeyUser(String userId, String email) {
        db.sql("INSERT INTO user_entities (id, name, display_name) VALUES (?, ?, ?)").params(userId, email, email)
                .update();
    }

    /** A passkey of a user of the passkey store; its key is not a real one, so it signs nobody in. */
    public void passkey(String credentialId, String userId, String label) {
        db.sql("""
                        INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible,
                                                      backup_state, label, created)
                        VALUES (?, ?, '\\x01', false, false, ?, now())
                        """)
                .params(credentialId, userId, label).update();
    }
}
