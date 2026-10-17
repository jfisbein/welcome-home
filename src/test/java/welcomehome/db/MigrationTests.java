package welcomehome.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import welcomehome.TestDatabaseConfiguration;

@SpringBootTest
@Import(TestDatabaseConfiguration.class)
class MigrationTests {

    @Autowired
    JdbcClient jdbc;

    @Test
    void migrationsRunAsMigrator() {
        var owner = jdbc.sql("SELECT tableowner FROM pg_tables WHERE tablename = 'databasechangelog'")
                .query(String.class)
                .single();

        assertThat(owner).isEqualTo("migrator");
    }

    @Test
    void createsInitialSchema() {
        var tables = jdbc.sql("""
                        SELECT tablename FROM pg_tables
                        WHERE schemaname = 'public' AND tablename NOT LIKE 'databasechangelog%'
                        """)
                .query(String.class)
                .list();

        assertThat(tables).containsExactlyInAnyOrder(
                "owner", "invitation", "user_entities", "user_credentials",
                "nuki_connection", "lock", "access_link", "access_event");
    }
}
