package welcomehome.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import welcomehome.access.DoorDistance.Check;
import welcomehome.access.DoorDistance.Verdict;
import welcomehome.locks.LocationCheck;
import welcomehome.nuki.Coordinates;

class DoorDistanceTests {

    static final Coordinates DOOR = new Coordinates(40.416775, -3.703790);

    @Test
    void theDistanceIsTheGreatCircleOne() {
        assertThat(DoorDistance.metres(DOOR, new GuestPosition(40.416775, -3.703790, 5))).isZero();
        assertThat(DoorDistance.metres(DOOR, new GuestPosition(40.417675, -3.703790, 5))).isCloseTo(100.0, within(0.5));
        // Puerta del Sol to Plaça de Catalunya, some 505 km.
        assertThat(DoorDistance.metres(DOOR, new GuestPosition(41.387015, 2.170047, 5)) / 1000).isCloseTo(505.0, within(2.0));
    }

    @Test
    void aGuestWithinTheRadiusOpensAndOneClearlyBeyondItIsAsked() {
        assertThat(check(LocationCheck.CONFIRM_WHEN_FAR, 50, 20, false)).isEqualTo(new Check(Verdict.OPEN, 50, 20, false));
        assertThat(check(LocationCheck.CONFIRM_WHEN_FAR, 3000, 20, false))
                .isEqualTo(new Check(Verdict.CONFIRM, 3000, 20, false));
        assertThat(check(LocationCheck.CONFIRM_WHEN_FAR, 3000, 20, true)).isEqualTo(new Check(Verdict.OPEN, 3000, 20, true));
    }

    /** Far only when the distance less the accuracy is beyond the radius. */
    @Test
    void aPoorAccuracyGivesTheBenefitOfTheDoubt() {
        assertThat(check(LocationCheck.REFUSE_WHEN_FAR, 300, 200, false).verdict()).isEqualTo(Verdict.OPEN);
        assertThat(check(LocationCheck.REFUSE_WHEN_FAR, 300, 100, false).verdict()).isEqualTo(Verdict.REFUSE);
    }

    @Test
    void theStricterModeRefusesAFarGuestEvenConfirmed() {
        assertThat(check(LocationCheck.REFUSE_WHEN_FAR, 3000, 20, true)).isEqualTo(new Check(Verdict.REFUSE, 3000, 20, false));
        assertThat(check(LocationCheck.REFUSE_WHEN_FAR, 50, 20, false)).isEqualTo(new Check(Verdict.OPEN, 50, 20, false));
    }

    @Test
    void anUnknownPositionIsAskedToConfirmInEitherMode() {
        for (var mode : new LocationCheck[] {LocationCheck.CONFIRM_WHEN_FAR, LocationCheck.REFUSE_WHEN_FAR}) {
            assertThat(DoorDistance.check(mode, 150, DOOR, null, false)).as(mode.name())
                    .isEqualTo(new Check(Verdict.CONFIRM, null, null, false));
            assertThat(DoorDistance.check(mode, 150, DOOR, null, true)).as(mode.name())
                    .isEqualTo(new Check(Verdict.OPEN, null, null, true));
        }
    }

    @Test
    void aPositionThatIsNoPlaceIsUnknownAndNeverPrinted() {
        assertThat(GuestPosition.of(91.0, 0.0, 10.0)).isNull();
        assertThat(GuestPosition.of(40.0, -3.0, Double.NaN)).isNull();
        assertThat(GuestPosition.of(40.0, null, 10.0)).isNull();
        assertThat(GuestPosition.of(40.41677, -3.70379, 10.0)).hasToString("GuestPosition[***]");
        assertThat(new OpenController.OpenRequest("secret-token", "lock",
                new OpenController.PositionRequest(40.41677, -3.70379, 10.0), true, "482913").toString())
                .doesNotContain("40.41677", "3.70379", "secret-token", "482913");
    }

    /** A guest due north of the door at this distance, with this accuracy, in metres. */
    private static Check check(LocationCheck mode, int metres, double accuracy, boolean confirmed) {
        var north = DOOR.latitude() + metres / (Math.PI * 6_371_000 / 180);
        return DoorDistance.check(mode, 150, DOOR, new GuestPosition(north, DOOR.longitude(), accuracy), confirmed);
    }
}
