package welcomehome.access;

import java.time.Duration;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

/**
 * In-memory rate limits for opening doors, one token bucket per IP address and one per link. Buckets
 * unused for a while are forgotten, so memory stays bounded. In memory is enough for a single instance.
 */
@Component
class RateLimiter {

    private static final int MAX_BUCKETS = 100_000;

    private final AccessProperties.RateLimit limits;
    private final Cache<String, Bucket> ipBuckets;
    private final Cache<UUID, Bucket> linkBuckets;

    RateLimiter(AccessProperties properties) {
        this.limits = properties.rateLimit();
        this.ipBuckets = newCache(limits.period());
        this.linkBuckets = newCache(limits.period());
    }

    /** @return whether the IP address may try once more, which uses up one attempt */
    boolean tryAttemptFromIp(String ip) {
        return ipBuckets.get(ip, _ -> newBucket(limits.perIp())).tryConsume(1);
    }

    /** @return whether the link may be tried once more, which uses up one attempt */
    boolean tryAttemptWithLink(UUID linkId) {
        return linkBuckets.get(linkId, _ -> newBucket(limits.perLink())).tryConsume(1);
    }

    private Bucket newBucket(int attempts) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(attempts).refillGreedy(attempts, limits.period()).build())
                .build();
    }

    /** A bucket untouched for twice the period is full again, so forgetting it changes nothing. */
    private static <K> Cache<K, Bucket> newCache(Duration period) {
        return Caffeine.newBuilder().maximumSize(MAX_BUCKETS).expireAfterAccess(period.multipliedBy(2)).build();
    }
}
