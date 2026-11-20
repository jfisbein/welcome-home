package welcomehome.auth;


import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads the owner behind a passkey: Spring Security's WebAuthn login calls it with the name of the passkey's
 * entry in its store, which is the owner's id ({@link OwnerName}).
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
    public UserDetails loadUserByUsername(String name) {
        var owner = OwnerName.ownerId(name).flatMap(owners::findById)
                .orElseThrow(() -> new UsernameNotFoundException("No owner by this name"));
        // Passkeys only: there is no password, and no endpoint accepts one.
        return User.withUsername(OwnerName.of(owner.id()))
                .password("")
                .roles(owner.isAdmin() ? new String[] {OWNER, ADMIN} : new String[] {OWNER})
                .build();
    }
}
