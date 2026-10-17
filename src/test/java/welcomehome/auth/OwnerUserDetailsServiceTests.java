package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;

@IntegrationTest
class OwnerUserDetailsServiceTests {

    @Autowired
    OwnerUserDetailsService userDetailsService;

    @Autowired
    PostgreSQLContainer postgres;

    @BeforeEach
    void insertOwners() {
        var migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        migrator.sql("INSERT INTO owner (email, is_admin) VALUES ('owner@example.com', false), ('admin@example.com', true)")
                .update();
    }

    @Test
    void ownerHasTheOwnerRole() {
        var owner = userDetailsService.loadUserByUsername("owner@example.com");

        assertThat(owner.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_OWNER");
    }

    @Test
    void adminAlsoHasTheAdminRole() {
        var admin = userDetailsService.loadUserByUsername("admin@example.com");

        assertThat(admin.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_OWNER", "ROLE_ADMIN");
    }

    @Test
    void emailIsCaseInsensitive() {
        assertThat(userDetailsService.loadUserByUsername("Owner@Example.com").getUsername()).isEqualTo("owner@example.com");
    }

    @Test
    void unknownEmailIsRejected() {
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("nobody@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
