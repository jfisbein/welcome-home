package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

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

    UUID ownerId;
    UUID adminId;

    @BeforeEach
    void insertOwners() {
        var migrator = TestDatabaseConfiguration.asMigrator(postgres);
        var data = new TestData(migrator);
        data.deleteOwners();
        ownerId = data.owner("owner@example.com");
        adminId = data.admin("admin@example.com");
    }

    @Test
    void ownerHasTheOwnerRole() {
        var owner = userDetailsService.loadUserByUsername(ownerId.toString());

        assertThat(owner.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_OWNER");
    }

    @Test
    void adminAlsoHasTheAdminRole() {
        var admin = userDetailsService.loadUserByUsername(adminId.toString());

        assertThat(admin.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_OWNER", "ROLE_ADMIN");
    }

    /** The name is the owner's id, which a change of email leaves as it is. */
    @Test
    void anOwnerIsKnownByTheirIdAndNotByTheirEmail() {
        assertThat(userDetailsService.loadUserByUsername(ownerId.toString()).getUsername()).isEqualTo(ownerId.toString());
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("owner@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void theIdOfNoOwnerIsRejected() {
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername(new UUID(0, 1).toString()))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
