package welcomehome.access;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestData;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.crypto.LinkTokenSigner;
import welcomehome.nuki.NukiStandInConfiguration;

/**
 * A door that checks where its guests are: what a slide comes to by the guest's distance, and what the history
 * keeps. Same configuration as OpenDoorTests, so they share one application context.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class LocationCheckTests {

    static final String ALICE = "alice@example.com";
    static final double DOOR_LATITUDE = 40.416775;
    static final double DOOR_LONGITUDE = -3.703790;
    /** Degrees of latitude per metre. */
    static final double NORTH = 1 / (Math.PI * 6_371_000 / 180);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    WireMockServer nuki;

    @Autowired
    LinkTokenSigner signer;

    @Autowired
    PostgreSQLContainer postgres;

    JdbcClient migrator;
    TestData data;
    UUID lockId;
    String token;
    /** Rate limits live in memory for the whole context: each test comes from its own IP address. */
    String ip;

    @BeforeEach
    void aliceWithALockAndALink() {
        nuki.resetAll();
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        data = new TestData(migrator);
        migrator.sql("DELETE FROM owner").update();
        data.owner(ALICE);
        var aliceId = migrator.sql("SELECT id FROM owner").query(UUID.class).single();
        lockId = data.lock(aliceId, 17, "Casa");
        nuki.stubFor(get("/smartlock").willReturn(okJson("[]")));
        NukiStandInConfiguration.logIsReadable(nuki);
        assertThat(mvc.put().uri("/settings/nuki").with(user(ALICE).roles("OWNER")).with(csrf())
                .param("apiToken", "alices-nuki-token")).hasStatus(HttpStatus.FOUND);
        nuki.resetAll();
        nukiOpens(17);
        token = signer.sign(data.link(lockId, "Ana"), 1);
        var random = ThreadLocalRandom.current();
        ip = "10.%d.%d.%d".formatted(random.nextInt(1, 255), random.nextInt(1, 255), random.nextInt(1, 255));
    }

    @Test
    void aGuestNearTheDoorOpensAndTheirDistanceIsKept() {
        data.locationCheck(lockId, DOOR_LATITUDE, DOOR_LONGITUDE, "CONFIRM_WHEN_FAR", 150);

        assertThat(open("lock", metresAway(50, 20), false)).hasStatusOk().bodyJson()
                .isEqualTo("{\"result\": \"opened\", \"distance\": 50}");

        assertThat(lastEvent()).isEqualTo("opened|50|20|false");
        assertUnlatches(1);
    }

    @Test
    void aGuestClearlyFarIsAskedToConfirmBeforeAnythingReachesTheLock() {
        data.locationCheck(lockId, DOOR_LATITUDE, DOOR_LONGITUDE, "CONFIRM_WHEN_FAR", 150);

        assertThat(open("lock", metresAway(3000, 20), false)).hasStatus(HttpStatus.PRECONDITION_REQUIRED)
                .bodyJson().isEqualTo("{\"result\": \"confirm-location\", \"distance\": 3000}");
        assertThat(lastEvent()).isEqualTo("confirm-location|3000|20|false");
        nuki.verify(0, anyRequestedFor(anyUrl()));

        assertThat(open("lock", metresAway(3000, 20), true)).hasStatusOk()
                .bodyJson().extractingPath("$.result").isEqualTo("opened");
        assertThat(lastEvent()).isEqualTo("opened|3000|20|true");
        assertUnlatches(1);
    }

    @Test
    void aPoorAccuracyGivesTheGuestTheBenefitOfTheDoubt() {
        data.locationCheck(lockId, DOOR_LATITUDE, DOOR_LONGITUDE, "REFUSE_WHEN_FAR", 150);

        assertThat(open("lock", metresAway(300, 200), false)).hasStatusOk()
                .bodyJson().extractingPath("$.result").isEqualTo("opened");
    }

    @Test
    void theStricterModeRefusesAFarGuestEvenWhenTheyConfirm() {
        data.locationCheck(lockId, DOOR_LATITUDE, DOOR_LONGITUDE, "REFUSE_WHEN_FAR", 150);

        assertThat(open("lock", metresAway(3000, 20), true)).hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().isEqualTo("{\"result\": \"too-far\", \"distance\": 3000}");
        assertThat(lastEvent()).isEqualTo("too-far|3000|20|true");
        nuki.verify(0, anyRequestedFor(anyUrl()));
    }

    /** A guest who refused the permission must not be left at the door, even by the stricter mode. */
    @Test
    void withoutAPositionTheGuestIsAskedToConfirmInEitherMode() {
        data.locationCheck(lockId, DOOR_LATITUDE, DOOR_LONGITUDE, "REFUSE_WHEN_FAR", 150);

        assertThat(open("lock", null, false)).hasStatus(HttpStatus.PRECONDITION_REQUIRED)
                .bodyJson().isEqualTo("{\"result\": \"confirm-location\"}");
        assertThat(lastEvent()).isEqualTo("confirm-location|-|-|false");

        assertThat(open("lock", null, true)).hasStatusOk().bodyJson().extractingPath("$.result").isEqualTo("opened");
        assertThat(lastEvent()).isEqualTo("opened|-|-|true");
    }

    @Test
    void aDoorThatDoesNotCheckIgnoresAnyPosition() {
        data.locationCheck(lockId, DOOR_LATITUDE, DOOR_LONGITUDE, "OFF", 150);

        assertThat(open("lock", metresAway(3000, 20), false)).hasStatusOk().bodyJson()
                .isEqualTo("{\"result\": \"opened\"}");
        assertThat(lastEvent()).isEqualTo("opened|-|-|-");
    }

    @Test
    void aLinkThatCannotOpenIsRefusedAsBeforeWithoutAskingWhereTheGuestIs() {
        data.locationCheck(lockId, DOOR_LATITUDE, DOOR_LONGITUDE, "CONFIRM_WHEN_FAR", 150);
        var expired = signer.sign(data.link(lockId, "Luis", "now() - interval '2 days'", "now() - interval '1 day'"), 1);

        assertThat(send("{\"token\": \"" + expired + "\"}")).hasStatus(HttpStatus.GONE)
                .bodyJson().extractingPath("$.result").isEqualTo("expired");
    }

    /** An Opener without a position of its own checks against its link's lock's. */
    @Test
    void theEntranceChecksAgainstItsOwnPositionOrElseItsLocks() {
        data.locationCheck(lockId, DOOR_LATITUDE, DOOR_LONGITUDE, "OFF", 150);
        var portal = data.entrance(lockId, 19, "Portal");
        migrator.sql("UPDATE lock SET location_check = 'CONFIRM_WHEN_FAR' WHERE id = ?").param(portal).update();
        nukiOpens(19);

        assertThat(open("entrance", metresAway(3000, 20), false)).hasStatus(HttpStatus.PRECONDITION_REQUIRED);

        data.locationCheck(portal, DOOR_LATITUDE + 3000 * NORTH, DOOR_LONGITUDE, "CONFIRM_WHEN_FAR", 150);
        assertThat(open("entrance", metresAway(3000, 20), false)).hasStatusOk()
                .bodyJson().isEqualTo("{\"result\": \"opened\", \"distance\": 0}");
    }

    @Test
    void theHistoryShowsHowFarTheGuestWas() {
        data.locationCheck(lockId, DOOR_LATITUDE, DOOR_LONGITUDE, "CONFIRM_WHEN_FAR", 150);
        open("lock", metresAway(3200, 20), false);

        assertThat(mvc.get().uri("/locks/" + lockId + "/history").with(user(ALICE).roles("OWNER"))).bodyText()
                .contains("a 3,2 km de la puerta", "Sin abrir: se pidió confirmar que estaba delante");
    }

    /** A position this far north of the door, with this accuracy, in metres, as the page sends it. */
    private static String metresAway(int metres, int accuracy) {
        return "{\"latitude\": %s, \"longitude\": %s, \"accuracy\": %d}"
                .formatted(DOOR_LATITUDE + metres * NORTH, DOOR_LONGITUDE, accuracy);
    }

    private MvcTestResult open(String door, @Nullable String position, boolean confirmed) {
        return send("{\"token\": \"%s\", \"door\": \"%s\", \"position\": %s, \"confirmed\": %s}"
                .formatted(token, door, position, confirmed));
    }

    private MvcTestResult send(String body) {
        return mvc.post().uri("/api/open")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private void nukiOpens(long smartlockId) {
        nuki.stubFor(post("/smartlock/" + smartlockId + "/action").willReturn(status(204)));
        nuki.stubFor(get(urlPathEqualTo("/smartlock/" + smartlockId + "/log")).willReturn(okJson("""
                [{"id": "1", "smartlockId": %d, "date": "%s", "action": 3, "trigger": 0, "state": 0}]
                """.formatted(smartlockId, Instant.now().plusSeconds(60)))));
    }

    private void assertUnlatches(int times) {
        assertThat(nuki.findAll(postRequestedFor(urlPathEqualTo("/smartlock/17/action")))).hasSize(times);
    }

    /** The latest attempt as "reason|distance|accuracy|confirmed", with "-" for none. */
    private String lastEvent() {
        return migrator.sql("""
                        SELECT concat_ws('|', reason, coalesce(distance_m::text, '-'), coalesce(accuracy_m::text, '-'),
                                         coalesce(location_confirmed::text, '-'))
                        FROM access_event ORDER BY occurred_at DESC LIMIT 1
                        """)
                .query(String.class).single();
    }
}
