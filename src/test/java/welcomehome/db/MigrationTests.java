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
}
