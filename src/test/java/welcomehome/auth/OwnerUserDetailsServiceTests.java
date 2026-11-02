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
import welcomehome.TestData;
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
        var data = new TestData(migrator);
        migrator.sql("DELETE FROM owner").update();
        data.owner("owner@example.com");
        data.admin("admin@example.com");
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
