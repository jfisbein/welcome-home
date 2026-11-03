package welcomehome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.nuki.NukiStandInConfiguration;

/**
 * A lock's time zone reads the same on every page: its name in the reader's language, as a guest reads it, never
 * its id. Only the lock's settings show ids, to choose among zones that share a name. Same configuration as the
 * locks tests, so they share one application context.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class TimeZoneNamesTests {

    static final String ALICE = "alice@example.com";
    static final String NEW_YORK = "America/New_York";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PostgreSQLContainer postgres;

    TestData data;
    UUID lockId;
    UUID linkId;

    @BeforeEach
    void aLockInNewYork() {
        data = new TestData(TestDatabaseConfiguration.asMigrator(postgres));
        data.deleteOwners();
        lockId = data.lock(data.owner(ALICE), 17, "Loft", NEW_YORK);
        linkId = data.link(lockId, "Ana");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/locks", "/locks/{lock}/links", "/links/{link}", "/locks/{lock}/history"})
    void everyPageNamesTheZoneAsGuestsReadIt(String page) {
        var uri = page.replace("{lock}", lockId.toString()).replace("{link}", linkId.toString());

        assertThat(mvc.get().uri(uri).with(owner())).hasStatusOk().bodyText()
                .contains("hora oriental")
                .doesNotContain(NEW_YORK);
    }

    @Test
    void theFormsAndTheHistorySayItInTheSameWords() {
        var words = "Horas en la zona horaria de la cerradura: hora oriental.";

        assertThat(mvc.get().uri("/locks/" + lockId + "/links").with(owner())).bodyText().contains(words);
        assertThat(mvc.get().uri("/links/" + linkId).with(owner())).bodyText().contains(words);
        assertThat(mvc.get().uri("/locks/" + lockId + "/history").with(owner())).bodyText().contains(words);
    }

    @Test
    void aCreatedLinkSaysItsZoneByName() {
        var zone = ZoneId.of(NEW_YORK);

        assertThat(mvc.get().uri("/locks/" + lockId + "/links").with(owner())
                .flashAttr("createdUrl", URI.create(IntegrationTest.BASE_URL + "/o/some-token"))
                .flashAttr("createdLabel", "Ana")
                .flashAttr("createdFrom", Instant.parse("2030-11-01T19:00:00Z").atZone(zone))
                .flashAttr("createdUntil", Instant.parse("2030-11-03T16:00:00Z").atZone(zone)))
                .bodyText().contains("Abre la puerta del 01/11/2030 15:00 al 03/11/2030 11:00 (hora oriental).");
    }

    @Test
    void theSettingsTellZonesThatShareANameApartByTheirIds() {
        assertThat(mvc.get().uri("/locks/" + lockId).with(owner())).hasStatusOk().bodyText()
                .contains(">America/New_York (hora oriental)</option>",
                        ">Europe/Berlin (hora de Europa central)</option>",
                        ">Europe/Madrid (hora de Europa central)</option>");
    }

    @Test
    void anOwnerReadsItInTheirLanguage() {
        data.deleteOwners();
        lockId = data.lock(data.owner(ALICE, false, "en"), 17, "Loft", NEW_YORK);

        assertThat(mvc.get().uri("/locks/" + lockId + "/history").with(owner())).bodyText()
                .contains("time zone: Eastern Time.");
    }

    private static RequestPostProcessor owner() {
        return user(ALICE).roles("OWNER");
    }
}
