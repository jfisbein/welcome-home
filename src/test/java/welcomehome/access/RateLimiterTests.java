package welcomehome.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import welcomehome.access.AccessProperties.RateLimit;

class RateLimiterTests {

    final RateLimiter limiter = new RateLimiter(new AccessProperties(new RateLimit(2, 3, Duration.ofHours(1))));

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
