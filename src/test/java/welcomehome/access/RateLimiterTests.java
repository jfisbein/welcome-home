package welcomehome.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import welcomehome.TestClock;
import welcomehome.access.AccessProperties.Confirmation;
import welcomehome.access.AccessProperties.RateLimit;

class RateLimiterTests {

    final TestClock clock = new TestClock();

    final RateLimiter limiter = new RateLimiter(new AccessProperties(new RateLimit(2, 3, Duration.ofHours(1)),
            new Confirmation(Duration.ofSeconds(15), Duration.ofSeconds(2)),
            new AccessProperties.Pin(2, Duration.ofHours(1))), clock);

    @Test
    void aLinkThatSpentItsWrongPinsIsRefusedUntilTheyComeBackOneByOne() {
        var link = UUID.randomUUID();
        assertThat(limiter.tryPin(link)).isTrue();
        assertThat(limiter.tryPin(link)).isTrue();
        assertThat(limiter.tryPin(link)).isFalse();
        assertThat(limiter.tryPin(UUID.randomUUID())).as("another link").isTrue();

        clock.advance(Duration.ofMinutes(29));
        assertThat(limiter.tryPin(link)).as("two an hour: none back yet").isFalse();
        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryPin(link)).as("one back after half an hour").isTrue();
        assertThat(limiter.tryPin(link)).isFalse();
    }

    @Test
    void aRightPinGivesBackWhatItTookAndNoMore() {
        var link = UUID.randomUUID();
        for (int opening = 0; opening < 10; opening++) {
            assertThat(limiter.tryPin(link)).as("opening " + opening).isTrue();
            limiter.pinWasRight(link);
        }
        limiter.pinWasRight(link);
        limiter.pinWasRight(link);

        assertThat(attempts(() -> limiter.tryPin(link))).as("never more than the link's wrong PINs").isEqualTo(2);
    }

    @Test
    void limitsComeFromTheConfiguration() {
        assertThat(attempts(() -> limiter.tryAttemptFromIp("192.0.2.1"))).isEqualTo(2);
        var link = UUID.randomUUID();
        assertThat(attempts(() -> limiter.tryAttemptWithLink(link))).isEqualTo(3);
    }

    @Test
    void eachIpAddressAndEachLinkHasItsOwnLimit() {
        attempts(() -> limiter.tryAttemptFromIp("192.0.2.1"));
        var link = UUID.randomUUID();
        attempts(() -> limiter.tryAttemptWithLink(link));

        assertThat(limiter.tryAttemptFromIp("192.0.2.2")).isTrue();
        assertThat(limiter.tryAttemptWithLink(UUID.randomUUID())).isTrue();
    }

    /** How many attempts succeed before the first refusal. */
    private static int attempts(BooleanSupplier attempt) {
        int allowed = 0;
        while (attempt.getAsBoolean()) {
            allowed++;
        }
        return allowed;
    }
}
