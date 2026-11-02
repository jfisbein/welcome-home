package welcomehome.links;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

class LinkScheduleTests {

    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    /** Far beyond every date below. */
    static final Instant NEVER = Instant.parse("2040-01-01T00:00:00Z");

    @Test
    void withoutHoursEveryTimeIsAllowed() {
        assertThat(LinkSchedule.ANY_TIME.allows(Instant.parse("2030-11-04T03:00:00Z"), MADRID)).isTrue();
    }

    @Test
    void hoursCoverFromTheirOpeningInclusiveToTheirClosingExclusiveOnTheirDayOnly() {
        var schedule = schedule(hours(DayOfWeek.MONDAY, "09:00", "17:00"));

        // 4 November 2030 is a Monday; Madrid is at UTC+1.
        assertThat(schedule.allows(Instant.parse("2030-11-04T07:59:59Z"), MADRID)).isFalse();
        assertThat(schedule.allows(Instant.parse("2030-11-04T08:00:00Z"), MADRID)).isTrue();
        assertThat(schedule.allows(Instant.parse("2030-11-04T15:59:59Z"), MADRID)).isTrue();
        assertThat(schedule.allows(Instant.parse("2030-11-04T16:00:00Z"), MADRID)).isFalse();
        assertThat(schedule.allows(Instant.parse("2030-11-05T09:00:00Z"), MADRID)).as("Tuesday").isFalse();
        assertThat(schedule.allows(Instant.parse("2030-11-11T09:00:00Z"), MADRID)).as("the next Monday").isTrue();
    }

    @Test
    void theDayIsTheLocksNotUtcs() {
        var schedule = schedule(hours(DayOfWeek.TUESDAY, "00:00", "01:00"));

        assertThat(schedule.allows(Instant.parse("2030-11-04T23:30:00Z"), MADRID)).as("already Tuesday in Madrid").isTrue();
        assertThat(schedule.allows(Instant.parse("2030-11-04T23:30:00Z"), ZoneId.of("UTC"))).as("Monday in UTC").isFalse();
    }

    @Test
    void hoursClosingBeforeTheyOpenEndTheNextDay() {
        var schedule = schedule(hours(DayOfWeek.SUNDAY, "22:00", "02:00"));

        // 10 November 2030 is a Sunday: its hours go on into Monday.
        assertThat(schedule.allows(Instant.parse("2030-11-10T20:59:00Z"), MADRID)).as("Sunday 21:59").isFalse();
        assertThat(schedule.allows(Instant.parse("2030-11-10T21:00:00Z"), MADRID)).as("Sunday 22:00").isTrue();
        assertThat(schedule.allows(Instant.parse("2030-11-11T00:59:00Z"), MADRID)).as("Monday 01:59").isTrue();
        assertThat(schedule.allows(Instant.parse("2030-11-11T01:00:00Z"), MADRID)).as("Monday 02:00").isFalse();
        assertThat(schedule.allows(Instant.parse("2030-11-10T00:30:00Z"), MADRID)).as("Sunday 01:30").isFalse();
        assertThat(schedule.allows(Instant.parse("2030-11-11T21:30:00Z"), MADRID)).as("Monday 22:30").isFalse();
    }

    @Test
    void midnightToMidnightIsTheWholeDayAndNoMore() {
        var schedule = schedule(hours(DayOfWeek.MONDAY, "00:00", "00:00"));

        assertThat(schedule.allows(Instant.parse("2030-11-03T23:00:00Z"), MADRID)).as("Monday 00:00").isTrue();
        assertThat(schedule.allows(Instant.parse("2030-11-04T22:59:00Z"), MADRID)).as("Monday 23:59").isTrue();
        assertThat(schedule.allows(Instant.parse("2030-11-04T23:00:00Z"), MADRID)).as("Tuesday 00:00").isFalse();
    }

    @Test
    void hoursFollowTheLocksClocksWhenTheyChange() {
        var schedule = schedule(hours(DayOfWeek.SATURDAY, "09:00", "17:00"), hours(DayOfWeek.SUNDAY, "09:00", "17:00"));

        // Madrid goes from UTC+1 to UTC+2 on Sunday, 31 March 2030.
        assertThat(schedule.allows(Instant.parse("2030-03-30T07:30:00Z"), MADRID)).as("08:30, the day before").isFalse();
        assertThat(schedule.allows(Instant.parse("2030-03-31T07:30:00Z"), MADRID)).as("09:30, the same UTC time").isTrue();
    }

    @Test
    void firstOpeningIsTheStartIfAllowedOrElseTheEarliestHoursToCome() {
        var schedule = schedule(hours(DayOfWeek.WEDNESDAY, "10:00", "12:00"), hours(DayOfWeek.MONDAY, "09:00", "17:00"));

        assertThat(firstOpening(schedule, "2030-11-04T10:00:00Z")).as("within Monday's hours").isEqualTo("2030-11-04T11:00");
        assertThat(firstOpening(schedule, "2030-11-04T07:00:00Z")).as("Monday, before they start").isEqualTo("2030-11-04T09:00");
        assertThat(firstOpening(schedule, "2030-11-04T20:00:00Z")).as("Monday, after them").isEqualTo("2030-11-06T10:00");
        assertThat(firstOpening(schedule, "2030-11-07T10:00:00Z")).as("the following week").isEqualTo("2030-11-11T09:00");
    }

    @Test
    void firstOpeningAfterThePeriodDoesNotCount() {
        var schedule = schedule(hours(DayOfWeek.MONDAY, "09:00", "17:00"));
        var tuesday = Instant.parse("2030-11-05T10:00:00Z");

        assertThat(schedule.firstOpening(tuesday, Instant.parse("2030-11-11T08:00:00Z"), MADRID)).as("ends as they start").isEmpty();
        assertThat(schedule.firstOpening(tuesday, Instant.parse("2030-11-11T08:00:01Z"), MADRID)).isPresent();
        assertThat(LinkSchedule.ANY_TIME.firstOpening(tuesday, tuesday, MADRID)).as("an empty period").isEmpty();
    }

    @Test
    void daysAreInOrderWhicheverWayTheyCame() {
        assertThat(schedule(hours(DayOfWeek.SUNDAY, "09:00", "17:00"), hours(DayOfWeek.MONDAY, "09:00", "17:00")).days())
                .extracting(DayHours::day).containsExactly(DayOfWeek.MONDAY, DayOfWeek.SUNDAY);
    }

    private static String firstOpening(LinkSchedule schedule, String from) {
        return schedule.firstOpening(Instant.parse(from), NEVER, MADRID).orElseThrow().toLocalDateTime().toString();
    }

    private static LinkSchedule schedule(DayHours... hours) {
        return new LinkSchedule(List.of(hours));
    }

    private static DayHours hours(DayOfWeek day, String opens, String closes) {
        return new DayHours(day, LocalTime.parse(opens), LocalTime.parse(closes));
    }
}
