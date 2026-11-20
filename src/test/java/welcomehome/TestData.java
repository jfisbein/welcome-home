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

    /**
     * Deletes every owner, and with them everything of theirs, passkeys included, and every invitation, also those
     * nobody created: what a test starts from.
     */
    public void deleteOwners() {
        db.sql("DELETE FROM invitation").update();
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

    /** Gives one owner a language for their pages and emails. */
    public void language(String email, String tag) {
        db.sql("UPDATE owner SET language = ? WHERE email = ?").params(tag, email).update();
    }

    /** Verifies every owner's email. */
    public void verifyEmails() {
        db.sql("UPDATE owner SET email_verified_at = now()").update();
    }

    /** Leaves an owner's email unverified, as before they open its link. */
    public void unverifyEmail(UUID owner) {
        db.sql("UPDATE owner SET email_verified_at = NULL WHERE id = ?").param(owner).update();
    }

    /** Leaves every owner's email unverified. */
    public void unverifyEmails() {
        db.sql("UPDATE owner SET email_verified_at = NULL").update();
    }

    /**
     * Says until when the link that verifies every owner's email can be opened.
     *
     * @param expiresAt an SQL expression, such as {@code now() - interval '1 second'}
     */
    public void emailVerificationExpires(String expiresAt) {
        db.sql("UPDATE owner SET email_verification_expires_at = " + expiresAt).update();
    }

    /**
     * Says until when the address an owner had gets the notices about their account.
     *
     * @param until an SQL expression
     */
    public void previousEmailUntil(UUID owner, String until) {
        db.sql("UPDATE owner SET previous_email_until = " + until + " WHERE id = ?").param(owner).update();
    }

    /**
     * An invitation that nobody holds the link of, as its token is made up from its note.
     *
     * @param createdBy the admin who created it, or null
     * @param expiresAt an SQL expression
     * @param used      whether it was redeemed, an hour before it expired
     */
    public void invitation(String note, @Nullable UUID createdBy, String expiresAt, boolean used) {
        db.sql("INSERT INTO invitation (token_hash, note, created_by, expires_at, used_at) "
                        + "VALUES (sha256(convert_to(:note, 'UTF8')), :note, :createdBy, " + expiresAt + ", "
                        + (used ? expiresAt + " - interval '1 hour'" : "NULL") + ")")
                .param("note", note).param("createdBy", createdBy).update();
    }

    /** Makes an invitation one that expired a minute ago. */
    public void expireInvitation(UUID invitation) {
        db.sql("UPDATE invitation SET expires_at = now() - interval '1 minute' WHERE id = ?").param(invitation)
                .update();
    }

    /** Deletes every passkey and every owner's entry in the passkey store. */
    public void deletePasskeys() {
        db.sql("DELETE FROM user_entities").update();
    }

    /** Names the passkeys of an owner, whose entry in the store shows their email. */
    public void passkeyLabel(String email, String label) {
        db.sql("UPDATE user_credentials SET label = ? FROM user_entities u "
                + "WHERE u.id = user_entity_user_id AND u.display_name = ?").params(label, email).update();
    }

    /** Marks one owner's Nuki connection as rejected by Nuki. */
    public void disconnectNuki(UUID owner) {
        db.sql("UPDATE nuki_connection SET status = 'DISCONNECTED' WHERE owner_id = ?").param(owner).update();
    }

    /** Marks every Nuki connection but one owner's as rejected by Nuki. */
    public void disconnectNukiOfAllBut(UUID owner) {
        db.sql("UPDATE nuki_connection SET status = 'DISCONNECTED' WHERE owner_id <> ?").param(owner).update();
    }

    /**
     * Says when Nuki last accepted or rejected every connection.
     *
     * @param at an SQL expression, such as {@code now() - interval '8 days'}
     */
    public void nukiLastChecked(String at) {
        db.sql("UPDATE nuki_connection SET last_checked_at = " + at).update();
    }

    /** Puts one owner's encrypted access token in another owner's connection, as a copied row would have it. */
    public void copyAccessToken(UUID from, UUID to) {
        db.sql("""
                        UPDATE nuki_connection SET access_token_encrypted =
                            (SELECT access_token_encrypted FROM nuki_connection WHERE owner_id = ?)
                        WHERE owner_id = ?
                        """).params(from, to).update();
    }

    /** Tells the owner's preferences that they want no emails about their locks' problems. */
    public void noLockProblemEmails(UUID owner) {
        db.sql("INSERT INTO notification_preference (owner_id, lock_problems) VALUES (?, false)").param(owner).update();
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

    /** Pauses a link now, as the app does: revoked, and marked as one its owner may resume. */
    public void pause(UUID link) {
        db.sql("UPDATE access_link SET revoked_at = now(), paused_at = now() WHERE id = ?").param(link).update();
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

    /**
     * A connection that works, with its access token already encrypted for its owner. Its refresh token is no token
     * at all: the access token does not expire for a day, so nothing refreshes it. A test about refreshing uses
     * {@link #authorization}.
     */
    public void connection(UUID owner, byte[] accessTokenEncrypted) {
        connection(owner, accessTokenEncrypted, "OK");
    }

    public void connection(UUID owner, byte[] accessTokenEncrypted, String status) {
        db.sql("""
                        INSERT INTO nuki_connection (owner_id, access_token_encrypted, refresh_token_encrypted,
                                                     access_token_expires_at, status)
                        VALUES (?, ?, ?, now() + interval '1 day', ?)
                        """)
                .params(owner, accessTokenEncrypted, accessTokenEncrypted, status).update();
    }

    /**
     * A connection authorized with OAuth2, its tokens already encrypted for their owner.
     *
     * @param expiresIn when the access token expires, from now, as an SQL interval such as {@code '1 hour'}
     */
    public void authorization(UUID owner, byte[] accessTokenEncrypted, byte[] refreshTokenEncrypted, String expiresIn,
                              String status) {
        db.sql("""
                        INSERT INTO nuki_connection (owner_id, access_token_encrypted,
                                                     refresh_token_encrypted, access_token_expires_at, status)
                        VALUES (?, ?, ?, now() + ?::interval, ?)
                        """)
                .params(owner, accessTokenEncrypted, refreshTokenEncrypted, expiresIn, status).update();
    }

    /** The owner's lock with that id in Nuki. */
    public UUID lockId(UUID owner, long nukiSmartlockId) {
        return db.sql("SELECT id FROM lock WHERE owner_id = ? AND nuki_smartlock_id = ?").params(owner, nukiSmartlockId)
                .query(UUID.class).single();
    }

    /** Deletes every lock, and with them their links and attempts. */
    public void deleteLocks() {
        db.sql("DELETE FROM lock").update();
    }

    /** Renames a lock and moves it to a time zone, as its owner does on its page. */
    public void rename(UUID lock, String name, String timeZone) {
        db.sql("UPDATE lock SET name = ?, time_zone = ? WHERE id = ?").params(name, timeZone, lock).update();
    }

    /** Moves a lock to a time zone. */
    public void timeZone(UUID lock, String timeZone) {
        db.sql("UPDATE lock SET time_zone = ? WHERE id = ?").params(timeZone, lock).update();
    }

    /** Says how a lock checks where its guests are, leaving its position and radius as they are. */
    public void locationCheck(UUID lock, String check) {
        db.sql("UPDATE lock SET location_check = ? WHERE id = ?").params(check, lock).update();
    }

    /**
     * Says since when Nuki has reported every lock offline.
     *
     * @param since an SQL expression
     */
    public void offlineSince(String since) {
        db.sql("UPDATE lock SET offline_since = " + since).update();
    }

    /** Says of every lock that something else locks its door again, so that Auto Lock off is no problem. */
    public void relockingHandledElsewhere() {
        db.sql("UPDATE lock SET relocking_handled_elsewhere = true").update();
    }

    /** Makes every lock an Opener. */
    public void makeOpeners() {
        db.sql("UPDATE lock SET device_type = 'OPENER'").update();
    }

    /** Deletes a link, and with it its attempts. */
    public void deleteLink(UUID link) {
        db.sql("DELETE FROM access_link WHERE id = ?").param(link).update();
    }

    /** Ends a link's pause or revocation, so that it is as before either. */
    public void resume(UUID link) {
        db.sql("UPDATE access_link SET revoked_at = NULL, paused_at = NULL WHERE id = ?").param(link).update();
    }

    /** Makes a revoked link a paused one, since when it was revoked. */
    public void pausedSinceRevoked(UUID link) {
        db.sql("UPDATE access_link SET paused_at = revoked_at WHERE id = ?").param(link).update();
    }

    /** Gives a link the version its current URL carries, as regenerating it does. */
    public void tokenVersion(UUID link, int version) {
        db.sql("UPDATE access_link SET token_version = ? WHERE id = ?").params(version, link).update();
    }

    /** Gives every link a token version. */
    public void tokenVersion(int version) {
        db.sql("UPDATE access_link SET token_version = ?").param(version).update();
    }

    /**
     * Moves every link's dates.
     *
     * @param validFrom  an SQL expression
     * @param validUntil an SQL expression
     */
    public void validity(String validFrom, String validUntil) {
        db.sql("UPDATE access_link SET valid_from = " + validFrom + ", valid_until = " + validUntil).update();
    }

    /** Says which country every attempt came from. */
    public void country(String country) {
        db.sql("UPDATE access_event SET country = ?").param(country).update();
    }

    /** Says which country the attempts from an IP address came from. */
    public void country(String ip, String country) {
        db.sql("UPDATE access_event SET country = ? WHERE ip = CAST(? AS inet)").params(country, ip).update();
    }

    /** Makes the attempts from an IP address attempts at an entrance. */
    public void atEntrance(String ip, UUID entrance) {
        db.sql("UPDATE access_event SET entrance_lock_id = ? WHERE ip = CAST(? AS inet)").params(entrance, ip).update();
    }

    /** Says of every attempt how far its guest was, and that they confirmed being at the door. */
    public void confirmedFrom(int distanceM, int accuracyM) {
        db.sql("UPDATE access_event SET distance_m = ?, accuracy_m = ?, location_confirmed = true")
                .params(distanceM, accuracyM).update();
    }

    /**
     * An entry of an owner's activity.
     *
     * @param age how long ago, as an SQL interval such as {@code 2 hours}
     */
    public void activity(UUID owner, String age, String kind, @Nullable UUID lock, @Nullable UUID link,
                         @Nullable String passkey, @Nullable String ip, @Nullable String country) {
        db.sql("""
                        INSERT INTO owner_activity (owner_id, occurred_at, kind, lock_id, link_id, passkey, ip, country)
                        VALUES (?, now() - CAST(? AS interval), ?, ?, ?, ?, CAST(? AS inet), ?)
                        """)
                .params(owner, age, kind, lock, link, passkey, ip, country).update();
    }

    /** The entry of an owner in Spring Security's passkey store, which knows its owner by their id. */
    public void passkeyUser(String userId, String email) {
        db.sql("INSERT INTO user_entities (id, name, display_name) SELECT ?, id::text, email FROM owner WHERE email = ?")
                .params(userId, email).update();
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
        db.sql("""
                        INSERT INTO nuki_connection (owner_id, access_token_encrypted, refresh_token_encrypted,
                                                     access_token_expires_at, status)
                        VALUES (?, ?, ?, now() + interval '1 day', 'OK') ON CONFLICT (owner_id) DO NOTHING
                        """)
                .params(owner, new byte[] {1}, new byte[] {2}).update();
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
