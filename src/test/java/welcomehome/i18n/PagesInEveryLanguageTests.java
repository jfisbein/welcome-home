package welcomehome.i18n;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.admin.InvitationService;
import welcomehome.config.Languages;
import welcomehome.crypto.CryptoService;
import welcomehome.crypto.LinkTokenSigner;
import welcomehome.nuki.NukiStandInConfiguration;

/**
 * Every page, in every language the app has texts for: it says which language it is in and asks for no text
 * that the bundle lacks (Thymeleaf shows a missing key as ??key_language??). A guest's pages are in the
 * browser's language, an owner's in the owner's. Alice is an admin with a lock, a
 * link and a guest's attempt, so that each page has something to show. Same configuration as the locks
 * tests, so they share one application context.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class PagesInEveryLanguageTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    WireMockServer nuki;

    @Autowired
    CryptoService crypto;

    @Autowired
    LinkTokenSigner signer;

    @Autowired
    InvitationService invitations;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    UUID lockId;
    UUID linkId;
    String invitationPath;
    String recoveryPath;

    @BeforeEach
    void insertAliceWithALockALinkAndAnAttempt() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        for (var table : new String[] {"user_credentials", "user_entities", "invitation", "owner"}) {
            migrator.sql("DELETE FROM " + table).update();
        }
        var aliceId = migrator.sql("INSERT INTO owner (email, is_admin) VALUES (?, true) RETURNING id").param(ALICE)
                .query(UUID.class).single();
        migrator.sql("INSERT INTO nuki_connection (owner_id, api_token_encrypted, status) VALUES (?, ?, 'OK')")
                .params(aliceId, crypto.encryptApiToken(aliceId, "alices-token")).update();
        lockId = migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, 17, 'Casa', 'Europe/Madrid') RETURNING id
                        """)
                .param(aliceId).query(UUID.class).single();
        linkId = migrator.sql("""
                        INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until)
                        VALUES (?, ?, 'Ana', now() - interval '1 day', now() + interval '1 day') RETURNING id
                        """)
                .params(aliceId, lockId).query(UUID.class).single();
        migrator.sql("""
                        INSERT INTO access_event (owner_id, link_id, lock_id, result, reason, ip)
                        VALUES (?, ?, ?, 'OK', 'opened', '192.0.2.10')
                        """)
                .params(aliceId, linkId, lockId).update();
        invitationPath = invitations.createInvitation(null, "Bea", false).url().getPath();
        recoveryPath = invitations.createPasskeyRecovery(null, aliceId).url().getPath();
        nuki.resetAll();
        nuki.stubFor(get("/smartlock/17").willReturn(okJson("""
                {"smartlockId": 17, "type": 5, "name": "Casa", "serverState": 0,
                 "state": {"state": 1, "batteryCritical": false, "batteryCharge": 80},
                 "advancedConfig": {"autoLock": false}}
                """)));
    }

    @Test
    void everyPageIsInTheLanguageAskedForWithEveryText() {
        var guestPages = List.of("/login", invitationPath, recoveryPath, "/invite/nonsense",
                "/o/" + signer.sign(linkId, 1), "/o/nonsense");
        var ownerPages = List.of("/", "/locks", "/locks/" + lockId, "/locks/" + lockId + "/links", "/links/" + linkId,
                "/locks/" + lockId + "/history", "/nuki/connection", "/notifications", "/passkeys",
                "/admin/owners", "/admin/invitations");

        for (var language : Languages.SUPPORTED) {
            var tag = language.getLanguage();
            for (var page : guestPages) {
                assertPage(page, tag, request -> request);
            }
            // An owner's pages are in the owner's language, whatever the browser asks for.
            migrator.sql("UPDATE owner SET language = ?").param(tag).update();
            for (var page : ownerPages) {
                assertPage(page, tag, user(ALICE).roles("OWNER", "ADMIN"));
            }
        }
    }

    @Test
    void theBrowsersFirstLanguageWithTextsWinsAndSpanishIsTheRest() {
        assertThat(login("en-GB,en;q=0.9")).contains("<html lang=\"en\"", "Sign in with a passkey");
        assertThat(login("de-DE,de;q=0.9,en;q=0.5")).contains("<html lang=\"en\"");
        assertThat(login("es-ES")).contains("<html lang=\"es\"", "Entrar con passkey");
        assertThat(login("fr-FR")).as("no bundle: the default").contains("<html lang=\"es\"", "Entrar con passkey");
        assertThat(body(mvc.get().uri("/login").exchange())).as("no Accept-Language").contains("<html lang=\"es\"");
    }

    @Test
    void aGuestReadsTheirLinkAndWhatTheButtonSaysInTheirLanguage() {
        assertThat(body(mvc.get().uri("/o/" + signer.sign(linkId, 1)).header(HttpHeaders.ACCEPT_LANGUAGE, "en").exchange()))
                .contains("This link opens the door from ", "(Europe/Madrid time)", ">Open<", "Door open: push it.")
                .doesNotContain("Abrir");
    }

    private String login(String acceptLanguage) {
        return body(mvc.get().uri("/login").header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage).exchange());
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void assertPage(String page, String language, RequestPostProcessor as) {
        var result = mvc.get().uri(page).header(HttpHeaders.ACCEPT_LANGUAGE, language).with(as).exchange();
        var body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).as(page + " in " + language)
                .contains("<html lang=\"" + language + "\"")
                .doesNotContain("??", "[[", "#{");
    }
}
