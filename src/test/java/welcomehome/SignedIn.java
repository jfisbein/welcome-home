package welcomehome;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.util.UUID;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A request made as a signed-in owner, for tests that do not sign in with a passkey. A session knows its owner by
 * their id, so the owner is looked up by their email as the request is made: they must exist by then. An email of
 * no owner signs in as nobody's id, which the app refuses as it does an owner deleted while signed in.
 */
public final class SignedIn {

    private static final String NOBODY = new UUID(0, 0).toString();

    private SignedIn() {
    }

    /**
     * Signs the request in as an owner.
     *
     * @param email the owner's email
     * @return what to give a request's {@code with}
     */
    public static RequestPostProcessor owner(String email) {
        return as(email, "OWNER");
    }

    /**
     * Signs the request in as an owner who is an admin.
     *
     * @param email the owner's email
     * @return what to give a request's {@code with}
     */
    public static RequestPostProcessor admin(String email) {
        return as(email, "OWNER", "ADMIN");
    }

    private static RequestPostProcessor as(String email, String... roles) {
        return request -> user(TestDatabaseConfiguration.ownerIdOf(email).map(UUID::toString).orElse(NOBODY))
                .roles(roles).postProcessRequest(request);
    }
}
