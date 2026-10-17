package welcomehome.links;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class LinkStatusTests {

    static final Instant FROM = Instant.parse("2030-11-01T14:00:00Z");
    static final Instant UNTIL = Instant.parse("2030-11-03T10:00:00Z");

    @Test
    void linkIsValidFromItsStartInclusiveToItsEndExclusive() {
        assertThat(status(FROM.minusMillis(1))).isEqualTo(LinkStatus.PENDING);
        assertThat(status(FROM)).isEqualTo(LinkStatus.ACTIVE);
        assertThat(status(UNTIL.minusMillis(1))).isEqualTo(LinkStatus.ACTIVE);
        assertThat(status(UNTIL)).isEqualTo(LinkStatus.EXPIRED);
    }

    @Test
    void revocationWinsOverTheDates() {
        for (var now : new Instant[] {FROM.minusSeconds(60), FROM, UNTIL}) {
            assertThat(LinkStatus.of(FROM, UNTIL, FROM.minusSeconds(3600), now)).isEqualTo(LinkStatus.REVOKED);
        }
    }

    @Test
    void onlyPendingAndActiveLinksAreUsable() {
        assertThat(LinkStatus.PENDING.isUsable()).isTrue();
        assertThat(LinkStatus.ACTIVE.isUsable()).isTrue();
        assertThat(LinkStatus.EXPIRED.isUsable()).isFalse();
        assertThat(LinkStatus.REVOKED.isUsable()).isFalse();
    }

    private static LinkStatus status(Instant now) {
        return LinkStatus.of(FROM, UNTIL, null, now);
    }
}
