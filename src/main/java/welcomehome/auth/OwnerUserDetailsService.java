package welcomehome.auth;

import java.util.Locale;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads the owner behind a passkey: Spring Security's WebAuthn login calls it with the passkey's user name,
 * which is the owner's email.
 */
@Service
class OwnerUserDetailsService implements UserDetailsService {

    static final String OWNER = "OWNER";
    static final String ADMIN = "ADMIN";

    private final OwnerAccountDao owners;

    OwnerUserDetailsService(OwnerAccountDao owners) {
        this.owners = owners;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        var owner = owners.findByEmail(email.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UsernameNotFoundException("No owner with this email"));
        // Passkeys only: there is no password, and no endpoint accepts one.
        return User.withUsername(owner.email())
                .password("")
                .roles(owner.isAdmin() ? new String[] {OWNER, ADMIN} : new String[] {OWNER})
                .build();
    }
}
