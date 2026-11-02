package welcomehome.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import welcomehome.IntegrationTest;
import welcomehome.TestDatabaseConfiguration;
import welcomehome.nuki.NukiStandInConfiguration;

/** Same configuration as the locks tests, so they share one application context. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(NukiStandInConfiguration.class)
class LinkScheduleFormTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";
    /** 4 November 2030 is a Monday. */
    static final String FROM = "2030-11-04T00:00";
    static final String UNTIL = "2030-12-04T00:00";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    LinkSchedules schedules;

    JdbcClient migrator;
    UUID lockId;

    @BeforeEach
    void insertALock() {
        migrator = TestDatabaseConfiguration.asMigrator(postgres);
        migrator.sql("DELETE FROM owner").update();
        lockId = insertLock(ALICE);
        insertLock(BOB);
    }

    @Test
    void aNewLinkOpensAtAnyTimeUnlessADayIsTicked() {
        assertThat(create("label", "Ana", "validFrom", FROM, "validUntil", UNTIL,
                "opens-MONDAY", "09:00", "closes-MONDAY", "17:00")).hasStatus(HttpStatus.FOUND);

        assertThat(rows()).as("the hours of a day not ticked count for nothing").isEmpty();
        assertThat(mvc.get().uri("/locks/" + lockId + "/links").with(owner(ALICE))).hasStatusOk()
                .bodyText().doesNotContain("<small>Lunes").contains("<details class=\"schedule\">");
    }

    @Test
    void ownerGivesEachDayItsHours() {
        var result = create("label", "Ana", "validFrom", FROM, "validUntil", UNTIL,
                "day-MONDAY", "on", "opens-MONDAY", "09:00", "closes-MONDAY", "17:00",
                "day-TUESDAY", "on", "opens-TUESDAY", "09:00", "closes-TUESDAY", "17:00",
                "opens-WEDNESDAY", "09:00", "closes-WEDNESDAY", "17:00",
                "day-SATURDAY", "on", "opens-SATURDAY", "10:00", "closes-SATURDAY", "14:00");

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(rows()).containsExactly("1|09:00:00|17:00:00", "2|09:00:00|17:00:00", "6|10:00:00|14:00:00");
        assertThat(result.getMvcResult().getFlashMap().get("createdSchedule"))
                .isEqualTo(List.of("Lunes a martes: 09:00–17:00", "Sábado: 10:00–14:00"));
        assertThat(mvc.get().uri("/locks/" + lockId + "/links").with(owner(ALICE))).hasStatusOk()
                .bodyText().contains("<small>Lunes a martes: 09:00–17:00</small>", "<small>Sábado: 10:00–14:00</small>");
    }

    @Test
    void hoursMayCrossMidnightOrTakeTheWholeDay() {
        assertThat(create("label", "Ana", "validFrom", FROM, "validUntil", UNTIL,
                "day-FRIDAY", "on", "opens-FRIDAY", "22:00", "closes-FRIDAY", "02:00",
                "day-SUNDAY", "on", "opens-SUNDAY", "00:00", "closes-SUNDAY", "00:00")).hasStatus(HttpStatus.FOUND);

        assertThat(rows()).containsExactly("5|22:00:00|02:00:00", "7|00:00:00|00:00:00");
        assertThat(mvc.get().uri("/locks/" + lockId + "/links").with(owner(ALICE))).hasStatusOk()
                .bodyText().contains("Viernes: 22:00–02:00", "Domingo: todo el día");
    }

    @Test
    void editPageShowsTheScheduleAndSavingReplacesIt() {
        create("label", "Ana", "validFrom", FROM, "validUntil", UNTIL,
                "day-MONDAY", "on", "opens-MONDAY", "08:30", "closes-MONDAY", "12:00");
        var link = linkId();

        assertThat(mvc.get().uri("/links/" + link).with(owner(ALICE))).hasStatusOk().bodyText()
                .contains("<details class=\"schedule\" open=\"open\">", "name=\"day-MONDAY\" checked=\"checked\"",
                        "value=\"08:30\"", "value=\"12:00\"")
                .doesNotContain("name=\"day-TUESDAY\" checked");

        assertThat(update(ALICE, link, "day-TUESDAY", "on", "opens-TUESDAY", "10:00", "closes-TUESDAY", "11:00"))
                .hasStatus(HttpStatus.FOUND);
        assertThat(rows()).containsExactly("2|10:00:00|11:00:00");

        assertThat(update(ALICE, link)).as("no day ticked any more").hasStatus(HttpStatus.FOUND);
        assertThat(rows()).isEmpty();
    }

    @Test
    void wrongHoursChangeNothingAndAreShownInTheirRow() {
        create("label", "Ana", "validFrom", FROM, "validUntil", UNTIL,
                "day-MONDAY", "on", "opens-MONDAY", "08:30", "closes-MONDAY", "12:00");
        var before = rows();

        for (var hours : new String[][] {{"", "17:00"}, {"09:00", ""}, {"nonsense", "17:00"}, {"09:00", "09:00"}}) {
            var result = update(ALICE, linkId(), "day-THURSDAY", "on", "opens-THURSDAY", hours[0], "closes-THURSDAY", hours[1]);

            assertThat(result).as(hours[0] + "-" + hours[1]).hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyText().contains("Indica una hora de inicio y una de fin distintas",
                            "<details class=\"schedule\" open=\"open\">", "name=\"day-THURSDAY\" checked=\"checked\"");
        }
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void aScheduleThatLeavesTheLinkNoTimeToOpenIsRefused() {
        var result = create("label", "Ana", "validFrom", "2030-11-05T00:00", "validUntil", "2030-11-06T00:00",
                "day-MONDAY", "on", "opens-MONDAY", "09:00", "closes-MONDAY", "17:00");

        assertThat(result).as("a Tuesday, and hours on Mondays").hasStatus(HttpStatus.BAD_REQUEST)
                .bodyText().contains("Con este horario, el enlace ya no abriría nunca",
                        "<details class=\"schedule\" open=\"open\">");
        assertThat(count()).isZero();

        assertThat(create("label", "Ana", "validFrom", "2020-01-01T00:00", "validUntil", "2030-11-06T00:00",
                "day-MONDAY", "on", "opens-MONDAY", "09:00", "closes-MONDAY", "17:00"))
                .as("Mondays to come").hasStatus(HttpStatus.FOUND);
    }

    @Test
    void regeneratingALinkKeepsItsSchedule() {
        create("label", "Ana", "validFrom", FROM, "validUntil", UNTIL,
                "day-MONDAY", "on", "opens-MONDAY", "09:00", "closes-MONDAY", "17:00");

        assertThat(mvc.post().uri("/links/" + linkId() + "/regenerate").with(owner(ALICE)).with(csrf()))
                .hasStatus(HttpStatus.FOUND);

        assertThat(rows()).containsExactly("1|09:00:00|17:00:00");
    }

    @Test
    void anotherOwnersLinkKeepsItsSchedule() {
        create("label", "Ana", "validFrom", FROM, "validUntil", UNTIL,
                "day-MONDAY", "on", "opens-MONDAY", "09:00", "closes-MONDAY", "17:00");

        assertThat(update(BOB, linkId(), "day-TUESDAY", "on", "opens-TUESDAY", "10:00", "closes-TUESDAY", "11:00"))
                .hasStatus(HttpStatus.NOT_FOUND);

        assertThat(rows()).containsExactly("1|09:00:00|17:00:00");
    }

    @Test
    void aScheduleIsSaidInTheReadersLanguageARunOfDaysToALine() {
        var schedule = new LinkSchedule(List.of(
                hours(DayOfWeek.MONDAY, "09:00", "17:00"), hours(DayOfWeek.TUESDAY, "09:00", "17:00"),
                hours(DayOfWeek.WEDNESDAY, "09:00", "17:00"), hours(DayOfWeek.FRIDAY, "09:00", "17:00"),
                hours(DayOfWeek.SATURDAY, "10:00", "14:00"), hours(DayOfWeek.SUNDAY, "00:00", "00:00")));

        assertThat(schedules.describe(schedule, Locale.of("es"))).containsExactly("Lunes a miércoles: 09:00–17:00",
                "Viernes: 09:00–17:00", "Sábado: 10:00–14:00", "Domingo: todo el día");
        assertThat(schedules.describe(schedule, Locale.ENGLISH)).containsExactly("Monday to Wednesday: 09:00–17:00",
                "Friday: 09:00–17:00", "Saturday: 10:00–14:00", "Sunday: all day");
        assertThat(schedules.describe(schedule, Locale.FRENCH)).containsExactly("Lundi au mercredi : 09:00–17:00",
                "Vendredi : 09:00–17:00", "Samedi : 10:00–14:00", "Dimanche : toute la journée");
        assertThat(schedules.describe(schedule, Locale.GERMAN)).containsExactly("Montag bis Mittwoch: 09:00–17:00",
                "Freitag: 09:00–17:00", "Samstag: 10:00–14:00", "Sonntag: ganztägig");
        assertThat(schedules.describe(schedule, Locale.ITALIAN)).containsExactly("Da lunedì a mercoledì: 09:00–17:00",
                "Venerdì: 09:00–17:00", "Sabato: 10:00–14:00", "Domenica: tutto il giorno");
        assertThat(schedules.describe(LinkSchedule.ANY_TIME, Locale.ENGLISH)).isEmpty();
    }

    private static DayHours hours(DayOfWeek day, String opens, String closes) {
        return new DayHours(day, LocalTime.parse(opens), LocalTime.parse(closes));
    }

    private MvcTestResult create(String... params) {
        var request = mvc.post().uri("/locks/" + lockId + "/links").with(owner(ALICE)).with(csrf());
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return request.exchange();
    }

    private MvcTestResult update(String email, UUID link, String... params) {
        var request = mvc.patch().uri("/links/" + link).with(owner(email)).with(csrf())
                .param("label", "Ana").param("validFrom", FROM).param("validUntil", UNTIL);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return request.exchange();
    }

    private UUID linkId() {
        return migrator.sql("SELECT id FROM access_link").query(UUID.class).single();
    }

    private int count() {
        return migrator.sql("SELECT count(*) FROM access_link").query(Integer.class).single();
    }

    private List<String> rows() {
        return migrator.sql("""
                        SELECT day_of_week || '|' || opens_at || '|' || closes_at
                        FROM access_link_schedule ORDER BY day_of_week
                        """)
                .query((rs, row) -> rs.getString(1)).list();
    }

    private UUID insertLock(String email) {
        var ownerId = migrator.sql("INSERT INTO owner (email) VALUES (?) RETURNING id").param(email)
                .query(UUID.class).single();
        return migrator.sql("""
                        INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
                        VALUES (?, 17, 'Casa', 'Europe/Madrid') RETURNING id
                        """)
                .param(ownerId).query(UUID.class).single();
    }

    private static RequestPostProcessor owner(String email) {
        return user(email).roles("OWNER");
    }
}
