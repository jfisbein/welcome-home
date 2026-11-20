package welcomehome.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;

/**
 * The {@code --migrate} command, as production's stack runs it before the application: with the migrating role's
 * credentials alone.
 */
@IntegrationTest
class MigrationCommandTests {

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void itBringsAnEmptyDatabaseToThisReleasesSchemaWithNothingButTheMigratingRolesCredentials() {
        var name = "migrate_" + UUID.randomUUID().toString().replace("-", "");
        var superuser = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
        superuser.sql("CREATE DATABASE " + name + " OWNER migrator").update();
        var url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        try {
            JdbcClient.create(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()))
                    .sql("ALTER SCHEMA public OWNER TO migrator").update();

            // No datasource of the application's, no keyset, no public URL: none of them is given.
            Migration.run(Migration.OPTION, "--spring.liquibase.url=" + url, "--spring.liquibase.user=migrator",
                    "--spring.liquibase.password=" + MigrationUpgradeTests.MIGRATOR_PASSWORD,
                    "--spring.main.banner-mode=off");

            var migrated = JdbcClient.create(new DriverManagerDataSource(url, "migrator",
                    MigrationUpgradeTests.MIGRATOR_PASSWORD));
            assertThat(migrated.sql("SELECT max(id) FROM databasechangelog").query(String.class).single())
                    .as("the latest changeset ran").startsWith("042-");
            assertThat(migrated.sql("SELECT count(*) FROM owner").query(Integer.class).single()).isZero();

            // Run again, as the stack does on every start: there is nothing left to do, and it says so by ending.
            Migration.run(Migration.OPTION, "--spring.liquibase.url=" + url, "--spring.liquibase.user=migrator",
                    "--spring.liquibase.password=" + MigrationUpgradeTests.MIGRATOR_PASSWORD,
                    "--spring.main.banner-mode=off");
        } finally {
            superuser.sql("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)").update();
        }
    }

    /** The application must not start after a migration that did not happen. */
    @Test
    void aMigrationThatCannotRunEndsInAnError() {
        assertThatThrownBy(() -> Migration.run(Migration.OPTION,
                "--spring.liquibase.url=" + postgres.getJdbcUrl(), "--spring.liquibase.user=migrator",
                "--spring.liquibase.password=not-the-password", "--spring.main.banner-mode=off"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void onlyItsArgumentAsksForIt() {
        assertThat(Migration.isAskedFor("--migrate")).isTrue();
        assertThat(Migration.isAskedFor("--bootstrap-admin")).isFalse();
        assertThat(Migration.isAskedFor()).isFalse();
    }
}
