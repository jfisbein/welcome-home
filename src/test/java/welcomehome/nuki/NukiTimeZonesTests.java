package welcomehome.nuki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class NukiTimeZonesTests {

    @Test
    void coversNukisFortySixIds() {
        assertThat(NukiTimeZones.BY_ID).hasSize(46);
        assertThat(NukiTimeZones.byId(0)).isEqualTo("Africa/Cairo");
        assertThat(NukiTimeZones.byId(37)).isEqualTo("Europe/Berlin");
        assertThat(NukiTimeZones.byId(45)).isEqualTo("Pacific/Pago_Pago");
    }

    @Test
    void everyZoneIsKnownToJava() {
        for (var zone : NukiTimeZones.BY_ID) {
            assertThatNoException().as(zone).isThrownBy(() -> ZoneId.of(zone));
        }
    }

    @Test
    void idsOutsideTheListHaveNoZone() {
        assertThat(NukiTimeZones.byId(null)).isNull();
        assertThat(NukiTimeZones.byId(-1)).isNull();
        assertThat(NukiTimeZones.byId(46)).isNull();
        assertThat(NukiTimeZones.byId(65535)).isNull();
    }
}
