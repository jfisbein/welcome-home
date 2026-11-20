package welcomehome.access;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.HexFormat;
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
 * In-memory rate limits for opening doors, one token bucket per network and one per link, and one per link for
 * wrong PINs. A network is an IPv4 address, or the /64 an IPv6 address is in: a single home or phone has a whole
 * /64 to itself, so counting each of its addresses apart would limit nothing. Buckets unused for a while are forgotten, so memory stays bounded. In memory is enough for a single
 * instance: what must outlast a restart, a link's total of wrong PINs, is {@link welcomehome.links.LinkPins}'s.
 */
@Component
class RateLimiter {

    private static final int MAX_BUCKETS = 100_000;

    private final TimeMeter clock;
    private final AccessProperties.RateLimit limits;
    private final Cache<String, Bucket> ipBuckets;
    private final Cache<UUID, Bucket> linkBuckets;
    private final Cache<UUID, Bucket> linkRefusals;
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
        this.linkRefusals = newCache(limits.period());
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

    /** @return whether the IP address's network may try once more, which uses up one attempt */
    boolean tryAttemptFromIp(String ip) {
        return ipBuckets.get(network(ip), _ -> newBucket(limits.perIp(), limits.period())).tryConsume(1);
    }

    /**
     * What attempts are counted by: an IPv4 address as it is, also when written as an IPv6 one
     * ({@code ::ffff:192.0.2.1}), and the first 64 bits of an IPv6 address. Anything else, as it came.
     */
    static String network(String ip) {
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(ip.strip());
        } catch (IllegalArgumentException e) {
            return ip;
        }
        if (!(address instanceof Inet6Address)) {
            return address.getHostAddress();
        }
        return HexFormat.of().formatHex(address.getAddress(), 0, 8) + "/64";
    }

    /**
     * Whether an attempt that {@link #tryAttemptWithLink} refused is worth telling the link's owner: one per period,
     * so that refusing attempts does not itself fill their history.
     */
    boolean tellOfRefusal(UUID linkId) {
        return linkRefusals.get(linkId, _ -> newBucket(1, limits.period())).tryConsume(1);
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
