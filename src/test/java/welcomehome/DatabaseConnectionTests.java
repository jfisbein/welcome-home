package welcomehome;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestDatabaseConfiguration.class)
class DatabaseConnectionTests {

    @Autowired
    JdbcClient jdbc;

    @Test
    void connectsAsUnprivilegedAppUser() {
        var role = jdbc.sql("""
                        SELECT rolname AS name,
                               rolsuper AS superuser,
                               rolbypassrls AS bypass_rls,
                               has_schema_privilege('public', 'CREATE') AS can_create_tables
                        FROM pg_roles
                        WHERE rolname = current_user
                        """)
                .query(DatabaseRole.class)
                .single();

        assertThat(role).isEqualTo(new DatabaseRole("app_user", false, false, false));
    }

    record DatabaseRole(String name, boolean superuser, boolean bypassRls, boolean canCreateTables) {
    }
}
