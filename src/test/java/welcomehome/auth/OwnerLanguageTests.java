package welcomehome.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
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

/** An owner's language: taken from the pages they register from, chosen on the panel, used on every page. */
@IntegrationTest
@AutoConfigureMockMvc
class OwnerLanguageTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    InvitationService invitations;

    /** The app's own connection, as app_user. */
    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;

    @BeforeEach
    void deleteOwners() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        for (var table : new String[] {"user_credentials", "user_entities", "invitation", "owner"}) {
            migrator.sql("DELETE FROM " + table).update();
        }
    }

    @Test
    void anOwnerKeepsTheLanguageTheyRegisteredIn() {
        register("ana@example.com", "en-GB,en;q=0.9");
        register("bea@example.com", "fr-FR");

        assertThat(language("ana@example.com")).isEqualTo("en");
        assertThat(language("bea@example.com")).as("no bundle for French: the default").isEqualTo("es");
    }

    @Test
    void anOwnersPagesAreInTheirLanguageWhateverTheBrowserAsks() {
        insertOwner("en");

        assertThat(home("es-ES")).contains("<html lang=\"en\"", "Signed in as");
    }

    @Test
    void theOwnerChoosesTheirLanguageInConfiguration() {
        insertOwner("es");
        assertThat(body(mvc.get().uri("/settings/language").with(owner()).exchange()))
                .containsPattern("<option value=\"es\" lang=\"es\"\\s+selected=\"selected\">Español</option>")
                .contains("<option value=\"en\" lang=\"en\">English</option>");

        assertThat(mvc.put().uri("/settings/language").with(owner()).with(csrf()).param("language", "en"))
                .hasStatus(HttpStatus.FOUND).redirectedUrl().isEqualTo("/settings/language");

        assertThat(language(ALICE)).isEqualTo("en");
        assertThat(home("es")).contains("<html lang=\"en\"", "Signed in as");
        assertThat(body(mvc.get().uri("/settings/language").with(owner()).flashAttr("languageSaved", true).exchange()))
                .contains("Language saved.");
    }

    @Test
    void onlyALanguageWithTextsCanBeChosen() {
        insertOwner("es");

        assertThat(mvc.put().uri("/settings/language").with(owner()).with(csrf()).param("language", "fr"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.put().uri("/settings/language").with(owner()).param("language", "en"))
                .as("without the CSRF token").hasStatus(HttpStatus.FORBIDDEN);
        assertThat(language(ALICE)).isEqualTo("es");
    }

    @Test
    void theAppMayChangeAnOwnersLanguageAndNothingElse() {
        insertOwner("es");

        jdbc.sql("UPDATE owner SET language = 'en'").update();
        for (var column : new String[] {"email = 'mallory@example.com'", "is_admin = false"}) {
            assertThatThrownBy(() -> jdbc.sql("UPDATE owner SET " + column).update()).as(column)
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("permission denied for table owner");
        }
    }

    private void register(String email, String acceptLanguage) {
        var invitation = invitations.createInvitation(null, email, false).url().getPath();
        var session = new MockHttpSession();
        var options = body(mvc.post().uri(invitation + "/options").session(session).with(csrf())
                .header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"" + email + "\"}").exchange());
        assertThat(mvc.post().uri(invitation + "/register").session(session).with(csrf())
                .header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage)
                .contentType(MediaType.APPLICATION_JSON)
                .content(new PasskeyEmulator(IntegrationTest.BASE_URL).create(options))).hasStatusOk();
    }

    private void insertOwner(String language) {
        migrator.sql("INSERT INTO owner (email, is_admin, language) VALUES (?, true, ?)").params(ALICE, language).update();
    }

    private String language(String email) {
        return migrator.sql("SELECT language FROM owner WHERE email = ?").param(email).query(String.class).single();
    }

    private String home(String acceptLanguage) {
        return body(mvc.get().uri("/").with(owner()).header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage).exchange());
    }

    private static String body(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static RequestPostProcessor owner() {
        return user(ALICE).roles("OWNER");
    }
}
