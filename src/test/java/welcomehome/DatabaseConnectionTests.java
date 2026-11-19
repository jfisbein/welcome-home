package welcomehome;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
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

    /**
     * As production's stack creates its database, and restore.sh one it restores: not by the C library's rules,
     * which change with the image and leave indexes that miss rows.
     */
    @Test
    void textIsComparedByPostgresOwnRulesWhateverTheImagesCLibrary() {
        assertThat(jdbc.sql("""
                        SELECT datlocprovider::text || ' ' || datlocale FROM pg_database
                        WHERE datname = current_database()
                        """)
                .query(String.class).single()).isEqualTo("b C.UTF-8");
        assertThat(jdbc.sql("SELECT string_agg(name, ',' ORDER BY name) FROM (VALUES ('casa'), ('Zeta'), ('ático')) "
                        + "AS names(name)").query(String.class).single())
                .as("by code point: capitals first, accented letters last").isEqualTo("Zeta,casa,ático");
    }

    record DatabaseRole(String name, boolean superuser, boolean bypassRls, boolean canCreateTables) {
    }
}
