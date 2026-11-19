package welcomehome;

import java.time.DayOfWeek;
import java.util.List;
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

    /** Makes an owner an admin. */
    public void makeAdmin(UUID owner) {
        db.sql("UPDATE owner SET is_admin = true WHERE id = ?").param(owner).update();
    }

    /** Counts an owner's email as verified, so that emails go to it. */
    public void verifyEmail(UUID owner) {
        db.sql("UPDATE owner SET email_verified_at = now() WHERE id = ?").param(owner).update();
    }

    /** Gives every owner a language for their pages and emails. */
    public void language(String tag) {
        db.sql("UPDATE owner SET language = ?").param(tag).update();
    }

    /** Marks every Nuki connection as rejected by Nuki, as the app does when a token stops working. */
    public void disconnectNuki() {
        db.sql("UPDATE nuki_connection SET status = 'DISCONNECTED'").update();
    }

    /** Deletes every Nuki connection, as for owners who never connected Nuki. */
    public void forgetNuki() {
        db.sql("DELETE FROM nuki_connection").update();
    }

    /** Marks a lock as no longer in its owner's Nuki account. */
    public void deactivate(UUID lock) {
        db.sql("UPDATE lock SET active = false WHERE id = ?").param(lock).update();
    }

    /** Marks every lock as no longer in its owner's Nuki account. */
    public void deactivateLocks() {
        db.sql("UPDATE lock SET active = false").update();
    }

    /** Marks every lock as in its owner's Nuki account again. */
    public void activateLocks() {
        db.sql("UPDATE lock SET active = true").update();
    }

    /** Revokes a link now. */
    public void revoke(UUID link) {
        db.sql("UPDATE access_link SET revoked_at = now() WHERE id = ?").param(link).update();
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

    /** How many wrong PINs a link has been given since its owner last edited it. */
    public void pinFailures(UUID link, int failures) {
        db.sql("UPDATE access_link SET pin_failures = ? WHERE id = ?").params(failures, link).update();
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

    /** An attempt at a door that checks where its guests are: how far the guest was, and whether they confirmed. */
    public void attemptFrom(UUID link, String occurredAt, String result, String reason, @Nullable Integer distanceM,
                            boolean confirmed) {
        db.sql("INSERT INTO access_event (owner_id, link_id, lock_id, occurred_at, result, reason, distance_m, "
                        + "location_confirmed) SELECT owner_id, id, lock_id, " + occurredAt + ", ?, ?, ?, ? "
                        + "FROM access_link WHERE id = ?")
                .params(result, reason, distanceM, confirmed, link).update();
    }

    /**
     * Where a lock is, and how it checks where its guests are.
     *
     * @param check {@code OFF}, {@code CONFIRM_WHEN_FAR} or {@code REFUSE_WHEN_FAR}
     */
    public void locationCheck(UUID lock, double latitude, double longitude, String check, int radiusM) {
        // The strictest check is stored as the one before it, with location_required (changeset 034).
        var required = "REQUIRE_NEAR".equals(check);
        db.sql("UPDATE lock SET latitude = ?, longitude = ?, location_check = ?, location_required = ?, "
                        + "location_radius_m = ? WHERE id = ?")
                .params(latitude, longitude, required ? "REFUSE_WHEN_FAR" : check, required, radiusM, lock).update();
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

    /**
     * A lock and a link of an owner's, as {@link #aRowInEveryTable} made them.
     *
     * @param lockId the lock
     * @param linkId the link
     */
    public record OwnerRows(UUID lockId, UUID linkId) {
    }

    /**
     * Gives an owner a row in every table that holds owners' data, for the tests of what keeps owners apart and of
     * what deleting one takes away: a connection, a lock, a link with a schedule and an attempt, their preferences
     * and an entry of their activity. A new table with an {@code owner_id} gets its row here, which those tests
     * then find missing until it does ({@link #ownerTables}).
     *
     * @param owner           the owner
     * @param nukiSmartlockId the id in Nuki of the lock to give them
     * @return the lock and the link
     */
    public OwnerRows aRowInEveryTable(UUID owner, long nukiSmartlockId) {
        db.sql("INSERT INTO nuki_connection (owner_id, access_token_encrypted, status) VALUES (?, ?, 'OK') "
                        + "ON CONFLICT (owner_id) DO NOTHING")
                .params(owner, new byte[] {1}).update();
        var lock = lock(owner, nukiSmartlockId, "Home");
        var link = link(lock, "Guest");
        schedule(link, DayOfWeek.MONDAY, "09:00", "17:00");
        attempt(link, "now()", "OK", "opened", null);
        db.sql("INSERT INTO notification_preference (owner_id) VALUES (?) ON CONFLICT (owner_id) DO NOTHING")
                .param(owner).update();
        db.sql("INSERT INTO owner_activity (owner_id, kind) VALUES (?, 'SIGNED_IN')").param(owner).update();
        return new OwnerRows(lock, link);
    }

    /**
     * The tables that hold owners' data, read from the schema: every one with an {@code owner_id}, so that a new one
     * is tried without anyone adding it to a list.
     *
     * @return their names, in order
     */
    public List<String> ownerTables() {
        return db.sql("""
                        SELECT c.relname FROM pg_class c
                        WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p')
                          AND EXISTS (SELECT 1 FROM pg_attribute a
                                      WHERE a.attrelid = c.oid AND a.attname = 'owner_id' AND NOT a.attisdropped)
                        ORDER BY c.relname
                        """)
                .query(String.class).list();
    }

    /**
     * How many rows an owner has in each of {@link #ownerTables}.
     *
     * @param owner the owner
     * @return the count by table, as "table: count"
     */
    public List<String> rowsByTable(UUID owner) {
        return ownerTables().stream()
                .map(table -> table + ": " + db.sql("SELECT count(*) FROM " + table + " WHERE owner_id = ?")
                        .param(owner).query(Long.class).single())
                .toList();
    }
}
