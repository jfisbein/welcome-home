package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.admin.InvitationService;

/** Alice registered on her phone; she adds her laptop, names, renames and deletes her passkeys. */
@IntegrationTest
@AutoConfigureMockMvc
class PasskeyManagementTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    InvitationService invitations;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    final PasskeyEmulator phone = new PasskeyEmulator(IntegrationTest.BASE_URL);
    final PasskeyEmulator laptop = new PasskeyEmulator(IntegrationTest.BASE_URL);

    @BeforeEach
    void registerAliceOnHerPhoneAndBob() {
        var migrator = TestDatabaseConfiguration.asMigrator(postgres);
        for (var table : new String[] {"user_credentials", "user_entities", "invitation", "owner"}) {
            migrator.sql("DELETE FROM " + table).update();
        }
        register(ALICE, phone);
        register(BOB, new PasskeyEmulator(IntegrationTest.BASE_URL));
        jdbc.sql("UPDATE user_credentials SET label = 'Móvil de Bob' FROM user_entities u "
                + "WHERE u.id = user_entity_user_id AND u.name = ?").param(BOB).update();
    }

    @Test
    void pageListsOnlyTheOwnersPasskeysAndHowToAddOne() {
        assertThat(mvc.get().uri("/passkeys").with(owner(ALICE)))
                .hasStatusOk()
                .bodyText().contains("Tus passkeys", "value=\"Passkey\"", "Añadir passkey", "/js/passkeys.js")
                .doesNotContain("Móvil de Bob");
        assertThat(mvc.get().uri("/").with(owner(ALICE))).bodyText().contains("href=\"/passkeys\"");
    }

    @Test
    void anAddedPasskeyHasItsNameAndSignsIn() {
        var session = new MockHttpSession();

        var options = body(post(session, "/webauthn/register/options", ""));
        assertThat(options).as("the phone's passkey is excluded").contains(credentialIdOf("Passkey"));
        var credential = laptop.create(options);
        assertThat(post(session, "/webauthn/register", "{\"publicKey\": {\"credential\": " + credential
                + ", \"label\": \"Portátil\"}}"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.success").isEqualTo(true);

        assertThat(mvc.get().uri("/passkeys").with(owner(ALICE))).bodyText().contains("value=\"Portátil\"");
        assertSignsIn(laptop, true);
        assertSignsIn(phone, true);
    }

    @Test
    void renamingChangesOnlyThatPasskey() {
        var result = mvc.patch().uri("/passkeys/" + credentialIdOf("Passkey")).with(owner(ALICE)).with(csrf())
                .param("label", "  iPhone  ").exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/passkeys");
        assertThat(labels()).containsExactlyInAnyOrder("iPhone", "Móvil de Bob");
    }

    @Test
    void aNameMustNotBeBlankOrTooLong() {
        for (var label : new String[] {"", "   ", "x".repeat(61)}) {
            assertThat(mvc.patch().uri("/passkeys/" + credentialIdOf("Passkey")).with(owner(ALICE)).with(csrf())
                    .param("label", label))
                    .as(label).hasStatus(HttpStatus.BAD_REQUEST);
        }
        assertThat(labels()).containsExactlyInAnyOrder("Passkey", "Móvil de Bob");
    }

    @Test
    void aDeletedPasskeyNoLongerSignsIn() {
        addLaptop();
        assertThat(mvc.get().uri("/passkeys").with(owner(ALICE))).bodyText()
                .contains("data-confirm=\"La passkey «Passkey» dejará de servir para entrar. ¿Borrarla?\"",
                        "/js/confirm.js");

        assertThat(mvc.delete().uri("/passkeys/" + credentialIdOf("Passkey")).with(owner(ALICE)).with(csrf()))
                .hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/passkeys");

        assertThat(labels()).containsExactlyInAnyOrder("Portátil", "Móvil de Bob");
        assertSignsIn(phone, false);
        assertSignsIn(laptop, true);
    }

    @Test
    void theLastPasskeyCannotBeDeleted() {
        assertThat(mvc.get().uri("/passkeys").with(owner(ALICE))).bodyText()
                .contains("Es tu única passkey").doesNotContain("data-confirm");

        assertThat(mvc.delete().uri("/passkeys/" + credentialIdOf("Passkey")).with(owner(ALICE)).with(csrf()))
                .hasStatus(HttpStatus.CONFLICT);

        assertThat(labels()).containsExactlyInAnyOrder("Passkey", "Móvil de Bob");
        assertSignsIn(phone, true);
    }

    @Test
    void changesRequireTheCsrfToken() {
        var id = credentialIdOf("Passkey");
        assertThat(mvc.patch().uri("/passkeys/" + id).with(owner(ALICE)).param("label", "x"))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.delete().uri("/passkeys/" + id).with(owner(ALICE))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.post().uri("/webauthn/register/options").with(owner(ALICE))).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void anonymousVisitorIsSentToLogin() {
        assertThat(mvc.get().uri("/passkeys")).hasStatus(HttpStatus.FOUND).redirectedUrl().endsWith("/login");
    }

    private void addLaptop() {
        var session = new MockHttpSession();
        var credential = laptop.create(body(post(session, "/webauthn/register/options", "")));
        assertThat(post(session, "/webauthn/register", "{\"publicKey\": {\"credential\": " + credential
                + ", \"label\": \"Portátil\"}}")).hasStatusOk();
    }

    private void register(String email, PasskeyEmulator device) {
        var invitation = invitations.createInvitation(null, email, false).url().getPath();
        var session = new MockHttpSession();
        var options = body(mvc.post().uri(invitation + "/options").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"" + email + "\"}").exchange());
        assertThat(mvc.post().uri(invitation + "/register").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(device.create(options))).hasStatusOk();
    }

    private void assertSignsIn(PasskeyEmulator device, boolean expected) {
        var visit = new MockHttpSession();
        var requestOptions = body(mvc.post().uri("/webauthn/authenticate/options").session(visit).with(csrf())
                .exchange());
        var result = mvc.post().uri("/login/webauthn").session(visit).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(device.get(requestOptions)).exchange();
        assertThat(result.getResponse().getStatus() == 200).as("signs in").isEqualTo(expected);
    }

    private String credentialIdOf(String label) {
        return jdbc.sql("""
                        SELECT c.credential_id FROM user_credentials c
                        JOIN user_entities u ON u.id = c.user_entity_user_id
                        WHERE u.name = ? AND c.label = ?
                        """)
                .params(ALICE, label).query(String.class).single();
    }

    private List<String> labels() {
        return jdbc.sql("SELECT label FROM user_credentials").query(String.class).list();
    }

    private MvcTestResult post(MockHttpSession session, String path, String json) {
        var request = mvc.post().uri(path).session(session).with(owner(ALICE)).with(csrf());
        return json.isEmpty() ? request.exchange()
                : request.contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private static String body(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static RequestPostProcessor owner(String email) {
        return user(email).roles("OWNER");
    }
}
