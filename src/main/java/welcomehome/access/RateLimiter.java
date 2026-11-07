package welcomehome.access;

import java.time.Duration;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

/**
 * In-memory rate limits for opening doors, one token bucket per IP address and one per link, and one per link for wrong PINs. Buckets
 * unused for a while are forgotten, so memory stays bounded. In memory is enough for a single instance.
 */
@Component
class RateLimiter {

    private static final int MAX_BUCKETS = 100_000;

    private final AccessProperties.RateLimit limits;
    private final Cache<String, Bucket> ipBuckets;
    private final Cache<UUID, Bucket> linkBuckets;
    private final AccessProperties.Pin pinLimit;
    private final Cache<UUID, Bucket> pinBuckets;

    RateLimiter(AccessProperties properties) {
        this.limits = properties.rateLimit();
        this.ipBuckets = newCache(limits.period());
        this.linkBuckets = newCache(limits.period());
        this.pinLimit = properties.pin();
        this.pinBuckets = newCache(pinLimit.period());
    }

    /** @return whether the link has spent its wrong PINs, so that it refuses even the right one for now */
    boolean pinLocked(UUID linkId) {
        var bucket = pinBuckets.getIfPresent(linkId);
        return bucket != null && bucket.getAvailableTokens() < 1;
    }

    /** Counts a wrong PIN against the link. */
    void pinFailed(UUID linkId) {
        pinBuckets.get(linkId, _ -> Bucket.builder().addLimit(Bandwidth.builder().capacity(pinLimit.failures())
                .refillGreedy(pinLimit.failures(), pinLimit.period()).build()).build()).tryConsume(1);
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
