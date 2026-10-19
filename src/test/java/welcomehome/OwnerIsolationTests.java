package welcomehome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.nuki.NukiStandInConfiguration;

/**
 * Alice gets a 404 from every endpoint that takes the id of one of Bob's resources, and Bob's data stays as
 * it was. A guard test makes every new route with an id join the list.
 * Same configuration as the locks tests, so they share one application context.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class OwnerIsolationTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";
    static final String BOBS_PASSKEY = "Ym9icy1wYXNza2V5";

    /** Routes whose id is a guest's or invitee's token, not an owner's resource: they have their own tests. */
    static final Set<String> TOKEN_ROUTES = Set.of(
            "GET /o/{token}",
            "GET /invite/{token}",
            "POST /invite/{token}/options",
            "POST /invite/{token}/register",
            "GET /email/verify/{token}",
            "POST /email/verify/{token}");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID alicesLock;
    UUID bobsLock;
    UUID bobsLink;

    @BeforeEach
    void insertAliceAndBob() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        migrator.sql("DELETE FROM user_credentials").update();
        migrator.sql("DELETE FROM user_entities").update();
        alicesLock = insertLock(insertOwner(ALICE), 17, "Casa de Alice");
        bobsLock = insertLock(insertOwner(BOB), 18, "Casa de Bob");
        bobsLink = migrator.sql("""
                        INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until)
                        SELECT owner_id, id, 'Invitado de Bob', now(), now() + interval '1 day' FROM lock WHERE id = ?
                        RETURNING id
                        """)
                .param(bobsLock).query(UUID.class).single();
        // Two passkeys, so that Bob may delete one of them.
        migrator.sql("INSERT INTO user_entities (id, name, display_name) VALUES ('Ym9i', ?, ?)").params(BOB, BOB).update();
        for (var credentialId : new String[] {BOBS_PASSKEY, "Ym9icy1vdGhlci1wYXNza2V5"}) {
            migrator.sql("""
                            INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible,
                                                          backup_state, label, created)
                            VALUES (?, 'Ym9i', '\\x01', false, false, 'Móvil de Bob', now())
                            """)
                    .param(credentialId).update();
        }
    }

    /** Each route with an id, and a request to it that Bob himself could make, in an order that lets him. */
    private List<Attempt> attemptsOnBobsResources() {
        return List.of(
                new Attempt("GET /locks/{lockId}", as -> mvc.get().uri("/locks/" + bobsLock).with(as).exchange()),
                new Attempt("PATCH /locks/{lockId}", as -> mvc.patch().uri("/locks/" + bobsLock).with(as).with(csrf())
                        .param("name", "Renombrada").param("timeZone", "Europe/Madrid").exchange()),
                new Attempt("GET /locks/{lockId}/links", as -> mvc.get().uri("/locks/" + bobsLock + "/links").with(as)
                        .exchange()),
                new Attempt("POST /locks/{lockId}/links", as -> mvc.post().uri("/locks/" + bobsLock + "/links").with(as)
                        .with(csrf()).param("label", "Intruso").param("validFrom", "2030-11-01T15:00")
                        .param("validUntil", "2030-11-03T11:00").exchange()),
                new Attempt("GET /locks/{lockId}/history", as -> mvc.get().uri("/locks/" + bobsLock + "/history")
                        .with(as).exchange()),
                new Attempt("GET /links/{linkId}", as -> mvc.get().uri("/links/" + bobsLink).with(as).exchange()),
                new Attempt("PATCH /links/{linkId}", as -> mvc.patch().uri("/links/" + bobsLink).with(as).with(csrf())
                        .param("label", "Intruso").param("validFrom", "2030-11-01T15:00")
                        .param("validUntil", "2030-11-03T11:00").exchange()),
                new Attempt("POST /links/{linkId}/regenerate", as -> mvc.post().uri("/links/" + bobsLink + "/regenerate")
                        .with(as).with(csrf()).exchange()),
                new Attempt("POST /links/{linkId}/revoke", as -> mvc.post().uri("/links/" + bobsLink + "/revoke")
                        .with(as).with(csrf()).exchange()),
                new Attempt("PATCH /passkeys/{credentialId}", as -> mvc.patch().uri("/passkeys/" + BOBS_PASSKEY)
                        .with(as).with(csrf()).param("label", "Intruso").exchange()),
                new Attempt("DELETE /passkeys/{credentialId}", as -> mvc.delete().uri("/passkeys/" + BOBS_PASSKEY)
                        .with(as).with(csrf()).exchange()));
    }

    @Test
    void aliceGetsNotFoundForEachOfBobsResourcesAndChangesNothing() {
        var before = bobsData();

        for (var attempt : attemptsOnBobsResources()) {
            assertThat(attempt.request().apply(owner(ALICE))).as(attempt.route()).hasStatus(HttpStatus.NOT_FOUND);
        }
        assertThat(mvc.get().uri("/locks/" + alicesLock + "/history?link=" + bobsLink).with(owner(ALICE)))
                .as("her own lock with his link").hasStatus(HttpStatus.NOT_FOUND);

        assertThat(bobsData()).isEqualTo(before);
    }

    @Test
    void bobCanMakeTheSameRequests() {
        for (var attempt : attemptsOnBobsResources()) {
            assertThat(attempt.request().apply(owner(BOB)).getResponse().getStatus()).as(attempt.route())
                    .isBetween(200, 399);
        }
    }

    @Test
    void everyRouteWithAnIdIsTried() {
        var routesWithAnId = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getMethodsCondition().getMethods().stream()
                        .flatMap(method -> info.getPatternValues().stream().map(path -> method + " " + path)))
                .filter(route -> route.contains("{"))
                .filter(route -> !TOKEN_ROUTES.contains(route))
                .collect(Collectors.toSet());

        assertThat(attemptsOnBobsResources().stream().map(Attempt::route))
                .containsExactlyInAnyOrderElementsOf(routesWithAnId);
    }

    /** Bob's lock, links and passkeys, row by row. */
    private List<String> bobsData() {
        return migrator.sql("""
                        SELECT l::text FROM lock l WHERE l.id = :lockId
                        UNION ALL
                        SELECT a::text FROM (SELECT * FROM access_link WHERE lock_id = :lockId ORDER BY id) a
                        UNION ALL
                        SELECT c::text FROM (SELECT * FROM user_credentials ORDER BY credential_id) c
                        """)
                .param("lockId", bobsLock)
                .query(String.class)
                .list();
    }

    private UUID insertOwner(String email) {
        return migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id").param(email).query(UUID.class).single();
    }

    private UUID insertLock(UUID ownerId, long nukiSmartlockId, String name) {
        return migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, ?, ?, 'Europe/Madrid') RETURNING id
                        """)
                .params(ownerId, nukiSmartlockId, name)
                .query(UUID.class)
                .single();
    }

    private static RequestPostProcessor owner(String email) {
        return user(email).roles("OWNER");
    }

    /**
     * @param route   the route as "METHOD /pattern"
     * @param request the request to Bob's resource, made as the given user
     */
    record Attempt(String route, Function<RequestPostProcessor, MvcTestResult> request) {
    }
}
