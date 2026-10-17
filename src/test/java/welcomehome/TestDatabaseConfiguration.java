package welcomehome;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Postgres in a container, initialised with the same role script as the real database.
 * The application connects as app_user, never as the container's superuser.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabaseConfiguration {

    static final String APP_USER_PASSWORD = "app_user";
    static final String MIGRATOR_PASSWORD = "migrator";

    @Bean
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17")
                .withCopyFileToContainer(
                        MountableFile.forHostPath("docker/db-init/01-create-roles.sh"),
                        "/docker-entrypoint-initdb.d/01-create-roles.sh")
                .withEnv("APP_USER_PASSWORD", APP_USER_PASSWORD)
                .withEnv("MIGRATOR_PASSWORD", MIGRATOR_PASSWORD);
    }

    @Bean
    DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", () -> "app_user");
            registry.add("spring.datasource.password", () -> APP_USER_PASSWORD);
        };
    }

    /**
     * Connects as the schema owner, for test setup that app_user is not allowed to do.
     *
     * @param postgres the test database container
     * @return a client that runs its statements as migrator
     */
    public static JdbcClient asMigrator(PostgreSQLContainer postgres) {
        return JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), "migrator", MIGRATOR_PASSWORD));
    }
}
