package welcomehome.access;

import java.time.Duration;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory rate limits for opening doors, one token bucket per IP address and one per link, and one per link for
 * wrong PINs. Buckets unused for a while are forgotten, so memory stays bounded. In memory is enough for a single
 * instance: what must outlast a restart, a link's total of wrong PINs, is {@link welcomehome.links.LinkPins}'s.
 */
@Component
class RateLimiter {

    private static final int MAX_BUCKETS = 100_000;

    private final TimeMeter clock;
    private final AccessProperties.RateLimit limits;
    private final Cache<String, Bucket> ipBuckets;
    private final Cache<UUID, Bucket> linkBuckets;
    private final AccessProperties.Pin pinLimit;
    private final Cache<UUID, Bucket> pinBuckets;

    /** With the system's clock, unless the context has another: tests move theirs forward. */
    @Autowired
    RateLimiter(AccessProperties properties, ObjectProvider<TimeMeter> clock) {
        this(properties, clock.getIfAvailable(() -> TimeMeter.SYSTEM_MILLISECONDS));
    }

    RateLimiter(AccessProperties properties, TimeMeter clock) {
        this.clock = clock;
        this.limits = properties.rateLimit();
        this.ipBuckets = newCache(limits.period());
        this.linkBuckets = newCache(limits.period());
        this.pinLimit = properties.pin();
        this.pinBuckets = newCache(pinLimit.period());
    }

    /**
     * Takes one of the link's wrong PINs before a PIN is compared, so that PINs sent at once cannot all be compared
     * on the one that was left. A PIN that turns out right gives it back with {@link #pinWasRight}.
     *
     * @return whether the PIN may be compared: false once the link has spent its wrong PINs, so that it refuses even
     * the right one for now
     */
    boolean tryPin(UUID linkId) {
        return pinBuckets.get(linkId, _ -> newBucket(pinLimit.failures(), pinLimit.period())).tryConsume(1);
    }

    /** Gives back what {@link #tryPin} took: only wrong PINs count against the link. */
    void pinWasRight(UUID linkId) {
        var bucket = pinBuckets.getIfPresent(linkId);
        if (bucket != null) {
            bucket.addTokens(1);
        }
    }

    /** @return whether the IP address may try once more, which uses up one attempt */
    boolean tryAttemptFromIp(String ip) {
        return ipBuckets.get(ip, _ -> newBucket(limits.perIp(), limits.period())).tryConsume(1);
    }

    /** @return whether the link may be tried once more, which uses up one attempt */
    boolean tryAttemptWithLink(UUID linkId) {
        return linkBuckets.get(linkId, _ -> newBucket(limits.perLink(), limits.period())).tryConsume(1);
    }

    private Bucket newBucket(int attempts, Duration period) {
        return Bucket.builder()
                .withCustomTimePrecision(clock)
                .addLimit(Bandwidth.builder().capacity(attempts).refillGreedy(attempts, period).build())
                .build();
    }

    /** A bucket untouched for twice the period is full again, so forgetting it changes nothing. */
    private static <K> Cache<K, Bucket> newCache(Duration period) {
        return Caffeine.newBuilder().maximumSize(MAX_BUCKETS).expireAfterAccess(period.multipliedBy(2)).build();
    }
}
