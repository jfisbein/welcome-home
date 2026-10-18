package welcomehome.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import welcomehome.IntegrationTest;

@IntegrationTest
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
                "nuki_connection", "lock", "access_link", "access_event", "notification_preference");
    }

    @Test
    void everyTableWithOwnerIdIsIsolatedByRowLevelSecurity() {
        var tables = jdbc.sql("""
                        SELECT c.relname AS name,
                               c.relrowsecurity AS row_security,
                               EXISTS (SELECT 1 FROM pg_policies p
                                       WHERE p.schemaname = 'public'
                                         AND p.tablename = c.relname
                                         AND p.cmd = 'ALL'
                                         AND p.qual LIKE '%current_owner_id()%'
                                         AND p.with_check LIKE '%current_owner_id()%') AS owner_policy
                        FROM pg_class c
                        WHERE c.relnamespace = 'public'::regnamespace
                          AND c.relkind IN ('r', 'p')
                          AND EXISTS (SELECT 1 FROM pg_attribute a
                                      WHERE a.attrelid = c.oid AND a.attname = 'owner_id' AND NOT a.attisdropped)
                        """)
                .query(TableSecurity.class)
                .list();

        assertThat(tables)
                .extracting(TableSecurity::name)
                .contains("nuki_connection", "lock", "access_link", "access_event");
        assertThat(tables)
                .as("tables with owner_id but without RLS enabled or an owner_id policy")
                .filteredOn(table -> !table.rowSecurity() || !table.ownerPolicy())
                .isEmpty();
    }

    record TableSecurity(String name, boolean rowSecurity, boolean ownerPolicy) {
    }
}
