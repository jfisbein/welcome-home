package welcomehome.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.icegreen.greenmail.util.GreenMail;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.auth.InvitationService;
import welcomehome.auth.PasskeyEmulator;
import welcomehome.notifications.MailStandInConfiguration;
import welcomehome.nuki.NukiStandInConfiguration;

/**
 * An owner's activity: what is recorded of their sign-ins and their links' changes, its page, and the email about a
 * link that may open the door from now on. Same configuration as AccountSecurityEmailTests, so they share one
 * application context.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({NukiStandInConfiguration.class, MailStandInConfiguration.class})
@TestPropertySource(properties = "spring.mail.host=127.0.0.1")
class OwnerActivityTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";
    static final String IP = "203.0.113.9";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    InvitationService invitations;

    @Autowired
    GreenMail mail;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;
    UUID aliceId;
    UUID lockId;

    @BeforeEach
    void aliceWithAVerifiedAddressAndALock() throws Exception {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        for (var table : new String[] {"user_credentials", "user_entities", "invitation", "owner"}) {
            migrator.sql("DELETE FROM " + table).update();
        }
        aliceId = data.owner(ALICE);
        migrator.sql("UPDATE owner SET email_verified_at = now() WHERE id = ?").param(aliceId).update();
        lockId = data.lock(aliceId, 17, "Casa");
        mail.purgeEmailFromAllMailboxes();
    }

    @Test
    void aSignInIsRecordedWithItsPasskeyAndWhereItCameFrom() {
        var phone = new PasskeyEmulator(IntegrationTest.BASE_URL);
        register(BOB, phone);
        assertThat(activity(BOB)).as("registering is not signing in").isEmpty();
        // The link that verifies Bob's address, which must not reach the next test.
        await().atMost(Duration.ofSeconds(5)).until(() -> mail.getReceivedMessages().length == 1);

        var visit = new MockHttpSession();
        var options = body(mvc.post().uri("/webauthn/authenticate/options").session(visit).with(csrf()).exchange());
        assertThat(mvc.post().uri("/login/webauthn").session(visit).with(csrf()).with(from(IP, "ES"))
                .contentType(MediaType.APPLICATION_JSON).content(phone.get(options))).hasStatusOk();

        assertThat(activity(BOB)).containsExactly("SIGNED_IN|Passkey|" + IP + "|ES|-|-");
    }

    @Test
    void everyChangeToALinkIsRecordedWithWhereItCameFrom() {
        assertThat(create("", "2030-11-01T15:00", "2030-11-03T11:00")).as("refused")
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(activity(ALICE)).as("a refused change is no change").isEmpty();

        assertThat(create("Ana", "2030-11-01T15:00", "2030-11-03T11:00")).hasStatus3xxRedirection();
        var link = linkId("Ana");
        assertThat(asAlice(mvc.patch().uri("/links/" + link).param("label", "Luis")
                .param("validFrom", "2030-11-02T10:00").param("validUntil", "2030-11-05T12:00")))
                .hasStatus3xxRedirection();
        assertThat(asAlice(mvc.post().uri("/links/" + link + "/regenerate"))).hasStatus3xxRedirection();
        assertThat(asAlice(mvc.post().uri("/links/" + link + "/revoke"))).hasStatus3xxRedirection();
        assertThat(asAlice(mvc.delete().uri("/links/" + link))).hasStatus3xxRedirection();

        var where = "|-|" + IP + "|FR|" + lockId + "|" + link;
        assertThat(activity(ALICE)).containsExactly("LINK_CREATED" + where, "LINK_EDITED" + where,
                "LINK_NEW_URL" + where, "LINK_REVOKED" + where, "LINK_DELETED" + where);
        // The emails about the first three, which must not reach the next test.
        await().atMost(Duration.ofSeconds(5)).until(() -> mail.getReceivedMessages().length == 3);
    }

    @Test
    void thePageShowsTheOwnersOwnActivityNewestFirst() {
        var ana = data.link(lockId, "Ana");
        var gone = UUID.randomUUID();
        insertActivity(aliceId, "3 hours", "SIGNED_IN", null, null, "iPhone", "198.51.100.1", "ES");
        insertActivity(aliceId, "2 hours", "LINK_CREATED", lockId, ana, null, "198.51.100.1", "ES");
        insertActivity(aliceId, "1 hour", "LINK_DELETED", lockId, gone, null, "198.51.100.2", null);
        var bobsLock = data.lock(data.owner(BOB), 18, "Apartamento de Bob");
        insertActivity(ownerOf(bobsLock), "1 hour", "SIGNED_IN", null, null, "Móvil de Bob", "198.51.100.3", "PT");

        var page = body(asAlice(mvc.get().uri("/settings/activity")));

        assertThat(page).contains("Actividad de tu cuenta", "Horas en la zona horaria del servidor:",
                        "Cada entrada se guarda 365 días, y su dirección IP, 90.", "aria-current=\"page\">Actividad<")
                .doesNotContain("Móvil de Bob", "198.51.100.3", "Solo se muestran");
        assertThat(order(page, "Enlace borrado", "Ya borrado, de Casa", "198.51.100.2",
                "Enlace creado", "Ana, de Casa", "🇪🇸", "Inicio de sesión", "Con la passkey «iPhone»")).isTrue();
        assertThat(body(asAlice(mvc.get().uri("/settings/nuki")))).contains("href=\"/settings/activity\"");
    }

    @Test
    void withoutActivityThePageSaysSo() {
        assertThat(body(asAlice(mvc.get().uri("/settings/activity")))).contains("Todavía no hay actividad.")
                .doesNotContain("<table>");
    }

    /** Never with the URL: the email may be read by someone else, and the URL opens the door. */
    @Test
    void aLinkThatMayOpenTheDoorFromNowOnIsEmailedWithoutItsUrl() throws Exception {
        create("Ana", "2030-11-01T15:00", "2030-11-03T11:00");
        var created = theOnlyEmail();
        assertThat(created.getSubject()).isEqualTo("Casa: enlace «Ana» creado");
        assertThat(created.getAllRecipients()).extracting(Object::toString).containsExactly(ALICE);
        assertThat(text(created)).contains(
                        "Se ha creado el enlace «Ana» para Casa, válido del 01/11/2030 15:00 al 03/11/2030 11:00 "
                                + "(hora de España peninsular).",
                        "anúlalo en " + IntegrationTest.BASE_URL + "/locks/" + lockId + "/links",
                        "actividad de tu cuenta en " + IntegrationTest.BASE_URL + "/settings/activity",
                        "Este aviso se envía siempre")
                .doesNotContain("/o/");

        var link = linkId("Ana");
        mail.purgeEmailFromAllMailboxes();
        asAlice(mvc.patch().uri("/links/" + link).param("label", "Luis")
                .param("validFrom", "2030-11-02T10:00").param("validUntil", "2030-11-05T12:00"));
        var edited = theOnlyEmail();
        assertThat(edited.getSubject()).isEqualTo("Casa: enlace «Luis» editado");
        assertThat(text(edited)).contains("ahora es válido del 02/11/2030 10:00 al 05/11/2030 12:00");

        mail.purgeEmailFromAllMailboxes();
        asAlice(mvc.post().uri("/links/" + link + "/regenerate"));
        var newUrl = theOnlyEmail();
        assertThat(newUrl.getSubject()).isEqualTo("Casa: URL nueva del enlace «Luis»");
        assertThat(text(newUrl)).contains("la anterior ya no abre la puerta").doesNotContain("/o/");
    }

    @Test
    void aLinkThatOpensNothingAnyMoreIsNoNews() {
        var link = data.link(lockId, "Ana", "now() - interval '2 days'", "now() - interval '1 day'");
        assertThat(asAlice(mvc.post().uri("/links/" + link + "/revoke"))).hasStatus3xxRedirection();
        assertThat(asAlice(mvc.delete().uri("/links/" + link))).hasStatus3xxRedirection();

        assertNoMoreThan(0);
    }

    /** The owner cannot turn it off, so whoever got into the account cannot turn it off first. */
    @Test
    void theEmailIsSentWhateverTheOwnersPreferencesInTheirLanguage() throws Exception {
        assertThat(asAlice(mvc.put().uri("/settings/notifications"))).hasStatus3xxRedirection();
        migrator.sql("UPDATE owner SET language = 'en'").update();

        create("Ana", "2030-11-01T15:00", "2030-11-03T11:00");

        var email = theOnlyEmail();
        assertThat(email.getSubject()).isEqualTo("Casa: link \"Ana\" created");
        assertThat(text(email)).contains("valid from 01/11/2030 15:00 to 03/11/2030 11:00 (mainland Spain time)",
                "This notification is always sent");
    }

    @Test
    void anAddressNotYetVerifiedGetsNoEmail() {
        migrator.sql("UPDATE owner SET email_verified_at = NULL").update();

        create("Ana", "2030-11-01T15:00", "2030-11-03T11:00");

        assertNoMoreThan(0);
    }

    private MvcTestResult create(String label, String validFrom, String validUntil) {
        return asAlice(mvc.post().uri("/locks/" + lockId + "/links")
                .param("label", label).param("validFrom", validFrom).param("validUntil", validUntil));
    }

    /** As Alice, from France, with the CSRF token. */
    private MvcTestResult asAlice(MockMvcTester.MockMvcRequestBuilder request) {
        return request.with(user(ALICE).roles("OWNER")).with(csrf()).with(from(IP, "FR")).exchange();
    }

    /** As Cloudflare says where a request came from. */
    private static RequestPostProcessor from(String ip, String country) {
        return request -> {
            request.addHeader("CF-Connecting-IP", ip);
            request.addHeader("CF-IPCountry", country);
            return request;
        };
    }

    private void register(String email, PasskeyEmulator device) {
        var invitation = invitations.createInvitation(null, email, false).url().getPath();
        var session = new MockHttpSession();
        var options = body(mvc.post().uri(invitation + "/options").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"" + email + "\"}").exchange());
        assertThat(mvc.post().uri(invitation + "/register").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(device.create(options))).hasStatusOk();
    }

    /** The owner's activity, oldest first, as "kind|passkey|ip|country|lock|link", with "-" for none. */
    private List<String> activity(String email) {
        return migrator.sql("""
                        SELECT concat_ws('|', a.kind, coalesce(a.passkey, '-'), coalesce(host(a.ip), '-'),
                                         coalesce(a.country, '-'), coalesce(a.lock_id::text, '-'),
                                         coalesce(a.link_id::text, '-'))
                        FROM owner_activity a JOIN owner o ON o.id = a.owner_id
                        WHERE o.email = ?
                        ORDER BY a.occurred_at, a.id
                        """)
                .param(email).query(String.class).list();
    }

    private void insertActivity(UUID ownerId, String age, String kind, UUID lock, UUID link, String passkey,
                                String ip, String country) {
        migrator.sql("""
                        INSERT INTO owner_activity (owner_id, occurred_at, kind, lock_id, link_id, passkey, ip, country)
                        VALUES (?, now() - CAST(? AS interval), ?, ?, ?, ?, CAST(? AS inet), ?)
                        """)
                .params(ownerId, age, kind, lock, link, passkey, ip, country).update();
    }

    private UUID linkId(String label) {
        return migrator.sql("SELECT id FROM access_link WHERE label = ?").param(label).query(UUID.class).single();
    }

    private UUID ownerOf(UUID lock) {
        return migrator.sql("SELECT owner_id FROM lock WHERE id = ?").param(lock).query(UUID.class).single();
    }

    /** Whether the texts appear in the page in this order. */
    private static boolean order(String page, String... texts) {
        var from = 0;
        for (var text : texts) {
            var at = page.indexOf(text, from);
            if (at < 0) {
                return false;
            }
            from = at + text.length();
        }
        return true;
    }

    private MimeMessage theOnlyEmail() {
        await().atMost(Duration.ofSeconds(5)).until(() -> mail.getReceivedMessages().length >= 1);
        assertNoMoreThan(1);
        return mail.getReceivedMessages()[0];
    }

    /** Waits a little for emails still on their way, which there should not be. */
    private void assertNoMoreThan(int count) {
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> mail.getReceivedMessages().length <= count);
        assertThat(mail.getReceivedMessages()).hasSize(count);
    }

    private static String body(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String text(MimeMessage email) throws Exception {
        return (String) email.getContent();
    }
}
