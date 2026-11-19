package welcomehome.links;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;

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
            assertThat(LinkStatus.of(FROM, UNTIL, FROM.minusSeconds(3600), false, now)).isEqualTo(LinkStatus.REVOKED);
        }
    }

    /** Past its end there is nothing left to resume: it has expired, as it would have without the pause. */
    @Test
    void aPausedLinkIsPausedUntilItsEndAndExpiredAfterIt() {
        var pausedAt = FROM.minusSeconds(3600);

        assertThat(LinkStatus.of(FROM, UNTIL, pausedAt, true, FROM.minusSeconds(60))).isEqualTo(LinkStatus.PAUSED);
        assertThat(LinkStatus.of(FROM, UNTIL, pausedAt, true, FROM)).isEqualTo(LinkStatus.PAUSED);
        assertThat(LinkStatus.of(FROM, UNTIL, pausedAt, true, UNTIL.minusMillis(1))).isEqualTo(LinkStatus.PAUSED);
        assertThat(LinkStatus.of(FROM, UNTIL, pausedAt, true, UNTIL)).isEqualTo(LinkStatus.EXPIRED);
    }

    /** The mark alone opens nothing and closes nothing: what closes a link is its revocation. */
    @Test
    void aLinkMarkedAsPausedThatIsNotRevokedGoesByItsDates() {
        assertThat(LinkStatus.of(FROM, UNTIL, null, true, FROM)).isEqualTo(LinkStatus.ACTIVE);
    }

    @Test
    void onlyPendingAndActiveLinksAreUsable() {
        assertThat(LinkStatus.PENDING.isUsable()).isTrue();
        assertThat(LinkStatus.ACTIVE.isUsable()).isTrue();
        assertThat(LinkStatus.EXPIRED.isUsable()).isFalse();
        assertThat(LinkStatus.REVOKED.isUsable()).isFalse();
        assertThat(LinkStatus.PAUSED.isUsable()).isFalse();
    }

    @Test
    void onlyExpiredAndRevokedLinksAreEnded() {
        assertThat(Arrays.stream(LinkStatus.values()).filter(LinkStatus::isEnded))
                .containsExactly(LinkStatus.EXPIRED, LinkStatus.REVOKED);
    }

    private static LinkStatus status(Instant now) {
        return LinkStatus.of(FROM, UNTIL, null, false, now);
    }
}
