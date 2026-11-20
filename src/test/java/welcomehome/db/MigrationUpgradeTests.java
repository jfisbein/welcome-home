package welcomehome.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilderFactory;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;

/**
 * Upgrades a database that has rows, as production's is upgraded: every other test migrates an empty one, where a
 * changeset that fills a column, deletes rows or adds a constraint has nothing to fail on. The database is taken to
 * where the major version began ({@link MigrationCompatibilityTests#COMPATIBLE_FROM}), given an owner with
 * everything an owner had then, and migrated to today's schema; then written to as a release from back then would,
 * as within a major version the previous release must run against the new schema.
 */
@IntegrationTest
class MigrationUpgradeTests {

    static final String CHANGELOG = "db/changelog/db.changelog-master.xml";
    static final String MIGRATOR_PASSWORD = "migrator";

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void aDatabaseWithRowsFromTheStartOfTheMajorVersionUpgradesAndStillTakesWhatAnEarlierReleaseWrites()
            throws Exception {
        var name = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var superuser = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
        superuser.sql("CREATE DATABASE " + name + " OWNER migrator").update();
        var url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        // As db-init does for the app's own database.
        JdbcClient.create(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()))
                .sql("ALTER SCHEMA public OWNER TO migrator").update();
        var migrator = JdbcClient.create(new DriverManagerDataSource(url, "migrator", MIGRATOR_PASSWORD));

        try (var connection = DriverManager.getConnection(url, "migrator", MIGRATOR_PASSWORD)) {
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(changeSetsBefore(MigrationCompatibilityTests.COMPATIBLE_FROM), new Contexts(),
                        new LabelExpression());
                var alice = anOwnerWithEverythingAnOwnerHadThen(migrator);

                liquibase.update(new Contexts(), new LabelExpression());

                assertThat(migrator.sql("SELECT count(*) FROM databasechangelog").query(Integer.class).single())
                        .as("every changeset ran").isEqualTo(changeSetsBefore(Integer.MAX_VALUE));
                assertThat(migrator.sql("""
                                SELECT concat_ws('|', (SELECT count(*) FROM owner), (SELECT count(*) FROM nuki_connection),
                                    (SELECT count(*) FROM lock), (SELECT count(*) FROM access_link),
                                    (SELECT count(*) FROM access_event), (SELECT count(*) FROM notification_preference))
                                """).query(String.class).single())
                        .as("the rows there were are there").isEqualTo("2|1|1|1|1|1");
                assertThat(migrator.sql("""
                                SELECT concat_ws('|', device_type, location_check, location_radius_m, location_required::text,
                                                 coalesce(entrance_lock_id::text, '-'), coalesce(latitude::text, '-'))
                                FROM lock
                                """).query(String.class).single())
                        .as("a lock from then is a smart lock that checks nothing").isEqualTo("SMART_LOCK|OFF|150|false|-|-");
                assertThat(migrator.sql("SELECT pin_failures || '|' || (pin_encrypted IS NULL) FROM access_link")
                        .query(String.class).single()).as("a link from then asks for no PIN").isEqualTo("0|true");
                assertThat(migrator.sql("SELECT name || '|' || coalesce(owner_id::text, '-') FROM user_entities ORDER BY name")
                        .query(String.class).list())
                        .as("passkeys' users are named by their owner's id, and tied to them")
                        .containsExactly(alice + "|" + alice);
                whatAReleaseFromThenWritesIsStillTaken(migrator, alice);
            }
        } finally {
            superuser.sql("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)").update();
        }
    }

    /**
     * What the changesets that began major version 4 do to the rows of a 3.x database, the only kind there is:
     * an owner's entry in the passkey store, named by their email, becomes named by their id, with the passkeys it
     * had, and an entry of no owner goes; the columns that went take nothing with them.
     */
    @Test
    void aDatabaseFromMajorVersion3KeepsItsOwnersPasskeysUnderTheirId() throws Exception {
        var name = "from3_" + UUID.randomUUID().toString().replace("-", "");
        var superuser = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
        superuser.sql("CREATE DATABASE " + name + " OWNER migrator").update();
        var url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        JdbcClient.create(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()))
                .sql("ALTER SCHEMA public OWNER TO migrator").update();
        var migrator = JdbcClient.create(new DriverManagerDataSource(url, "migrator", MIGRATOR_PASSWORD));

        try (var connection = DriverManager.getConnection(url, "migrator", MIGRATOR_PASSWORD)) {
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(changeSetsBefore(40), new Contexts(), new LabelExpression());
                var alice = migrator.sql("INSERT INTO owner (email) VALUES ('alice@example.com') RETURNING id")
                        .query(UUID.class).single();
                migrator.sql("""
                        INSERT INTO user_entities (id, name, display_name)
                        VALUES ('alice', 'alice@example.com', 'alice@example.com'), ('gone', 'gone@example.com', 'gone')
                        """).update();
                migrator.sql("""
                        INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible,
                                                      backup_state, label)
                        VALUES ('phone', 'alice', '\\x01', false, false, 'Phone'),
                               ('laptop', 'alice', '\\x01', false, false, 'Laptop'),
                               ('nobodys', 'gone', '\\x01', false, false, 'Passkey')
                        """).update();
                var connectionId = migrator.sql("""
                        INSERT INTO nuki_connection (owner_id, access_token_encrypted, refresh_token_encrypted,
                                                     access_token_expires_at, status)
                        VALUES (?, '\\x01', '\\x02', now() + interval '1 hour', 'OK') RETURNING id
                        """).param(alice).query(UUID.class).single();
                var lock = migrator.sql("""
                        INSERT INTO lock (owner_id, connection_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, ?, 17, 'Casa', 'Europe/Madrid') RETURNING id
                        """).params(alice, connectionId).query(UUID.class).single();
                var link = migrator.sql("""
                        INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until)
                        VALUES (?, ?, 'Ana', now(), now() + interval '1 day') RETURNING id
                        """).params(alice, lock).query(UUID.class).single();
                migrator.sql("INSERT INTO access_event (owner_id, link_id, lock_id, result) VALUES (?, ?, ?, 'OK')")
                        .params(alice, link, lock).update();

                liquibase.update(new Contexts(), new LabelExpression());

                assertThat(migrator.sql("SELECT concat_ws('|', id, name, display_name, owner_id) FROM user_entities")
                        .query(String.class).list())
                        .as("her entry keeps its id, which ties her passkeys to it, and goes by her id, showing her email")
                        .containsExactly("alice|" + alice + "|alice@example.com|" + alice);
                assertThat(migrator.sql("SELECT credential_id FROM user_credentials ORDER BY credential_id")
                        .query(String.class).list()).as("her passkeys, and not nobody's").containsExactly("laptop", "phone");
                assertThat(migrator.sql("""
                                SELECT concat_ws('|', (SELECT count(*) FROM nuki_connection WHERE owner_id = ?),
                                    (SELECT count(*) FROM lock), (SELECT count(*) FROM access_link),
                                    (SELECT count(*) FROM access_event))
                                """).param(alice).query(String.class).single())
                        .as("her connection, lock, link and attempt are there").isEqualTo("1|1|1|1");
            }
        } finally {
            superuser.sql("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)").update();
        }
    }

    /** Changeset 043: the mode between asking and requiring is gone, and its locks ask. */
    @Test
    void locksThatRefusedOnlyAGuestClearlyFarAskThemToConfirmAndTheStrictOnesStayStrict() throws Exception {
        var name = "modes_" + UUID.randomUUID().toString().replace("-", "");
        var superuser = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
        superuser.sql("CREATE DATABASE " + name + " OWNER migrator").update();
        var url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        JdbcClient.create(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()))
                .sql("ALTER SCHEMA public OWNER TO migrator").update();
        var migrator = JdbcClient.create(new DriverManagerDataSource(url, "migrator", MIGRATOR_PASSWORD));

        try (var connection = DriverManager.getConnection(url, "migrator", MIGRATOR_PASSWORD)) {
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(changeSetsBefore(43), new Contexts(), new LabelExpression());
                var alice = migrator.sql("INSERT INTO owner (email) VALUES ('alice@example.com') RETURNING id")
                        .query(UUID.class).single();
                migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone, latitude, longitude,
                                          location_check, location_required)
                        VALUES (:alice, 1, 'Off', 'Europe/Madrid', NULL, NULL, 'OFF', false),
                               (:alice, 2, 'Asks', 'Europe/Madrid', 40.4, -3.7, 'CONFIRM_WHEN_FAR', false),
                               (:alice, 3, 'Refused', 'Europe/Madrid', 40.4, -3.7, 'REFUSE_WHEN_FAR', false),
                               (:alice, 4, 'Strict', 'Europe/Madrid', 40.4, -3.7, 'REFUSE_WHEN_FAR', true)
                        """).param("alice", alice).update();

                liquibase.update(new Contexts(), new LabelExpression());

                assertThat(migrator.sql("""
                                SELECT name || '|' || location_check || '|' || location_required FROM lock
                                ORDER BY nuki_smartlock_id
                                """).query(String.class).list())
                        .containsExactly("Off|OFF|false", "Asks|CONFIRM_WHEN_FAR|false",
                                "Refused|CONFIRM_WHEN_FAR|false", "Strict|REFUSE_WHEN_FAR|true");
            }
        } finally {
            superuser.sql("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)").update();
        }
    }

    /** Only the columns there were then, as a release from then knows no others. */
    private static UUID anOwnerWithEverythingAnOwnerHadThen(JdbcClient migrator) {
        var alice = migrator.sql("INSERT INTO owner (email) VALUES ('alice@example.com') RETURNING id")
                .query(UUID.class).single();
        migrator.sql("INSERT INTO owner (email) VALUES ('bob@example.com')").update();
        migrator.sql("INSERT INTO user_entities (id, name) VALUES ('alice', ?)").param(alice.toString()).update();
        migrator.sql("""
                        INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible,
                                                      backup_state, label)
                        VALUES ('alices-passkey', 'alice', '\\x01', false, false, 'Passkey')
                        """).update();
        migrator.sql("""
                        INSERT INTO nuki_connection (owner_id, access_token_encrypted, refresh_token_encrypted,
                                                     access_token_expires_at, status)
                        VALUES (?, '\\x01', '\\x02', now() + interval '1 hour', 'OK')
                        """).param(alice).update();
        var lock = migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, 17, 'Casa', 'Europe/Madrid') RETURNING id
                        """).param(alice).query(UUID.class).single();
        var link = migrator.sql("""
                        INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until)
                        VALUES (?, ?, 'Ana', now(), now() + interval '1 day') RETURNING id
                        """).params(alice, lock).query(UUID.class).single();
        migrator.sql("INSERT INTO access_event (owner_id, link_id, lock_id, result) VALUES (?, ?, ?, 'OK')")
                .params(alice, link, lock).update();
        migrator.sql("INSERT INTO notification_preference (owner_id) VALUES (?)").param(alice).update();
        return alice;
    }

    /** The same statements again, against today's schema: its new columns have defaults, its constraints hold. */
    private static void whatAReleaseFromThenWritesIsStillTaken(JdbcClient migrator, UUID alice) {
        var lock = migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, 18, 'Garaje', 'Europe/Madrid') RETURNING id
                        """).param(alice).query(UUID.class).single();
        var link = migrator.sql("""
                        INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until)
                        VALUES (?, ?, 'Bea', now(), now() + interval '1 day') RETURNING id
                        """).params(alice, lock).query(UUID.class).single();
        migrator.sql("INSERT INTO access_event (owner_id, link_id, lock_id, result) VALUES (?, ?, ?, 'OK')")
                .params(alice, link, lock).update();
        migrator.sql("INSERT INTO user_entities (id, name) SELECT 'bob', id::text FROM owner WHERE email = 'bob@example.com'")
                .update();

        assertThat(migrator.sql("SELECT owner_id::text = name FROM user_entities WHERE id = 'bob'")
                .query(Boolean.class).single())
                .as("a passkey's user that a release from then stores, without its owner, gets it from the trigger")
                .isTrue();
        assertThat(migrator.sql("SELECT count(*) FROM lock").query(Integer.class).single()).isEqualTo(2);
    }

    /** How many changesets the changelogs numbered below a number hold, in the order Liquibase runs them. */
    private static int changeSetsBefore(int number) throws Exception {
        var count = 0;
        for (var file : new PathMatchingResourcePatternResolver().getResources("classpath:db/changelog/changes/*.xml")) {
            var name = file.getFilename();
            if (name != null && Integer.parseInt(name.substring(0, name.indexOf('-'))) < number) {
                count += DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.getInputStream())
                        .getElementsByTagName("changeSet").getLength();
            }
        }
        assertThat(count).isPositive();
        return count;
    }
}
