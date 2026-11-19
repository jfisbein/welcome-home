package welcomehome;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.jspecify.annotations.Nullable;

/**
 * The app's clock in tests: the real one, until a test sets it. A test whose outcome depends on what time it is
 * either stops it ({@link #freeze()}), so that what the test and the app each read cannot fall on two sides of
 * midnight, or sets it ({@link #setTo(Instant)}), so that the dates it sends stay in the future whenever it runs.
 * {@link IntegrationTest} puts it back after every test. The database's {@code now()} stays the real time: a test
 * that sets the clock far from it must not also rely on rows dated by the database.
 */
public class TestTime extends Clock {

    private volatile @Nullable Instant fixed;

    /**
     * Stops the clock at an instant.
     *
     * @param instant what time it is from now on
     */
    public void setTo(Instant instant) {
        fixed = instant;
    }

    /**
     * Stops the clock where it is.
     *
     * @return the instant it stays at
     */
    public Instant freeze() {
        var now = Instant.now();
        fixed = now;
        return now;
    }

    /** Lets the clock run again, as the real one. */
    public void reset() {
        fixed = null;
    }

    @Override
    public Instant instant() {
        var stopped = fixed;
        return stopped != null ? stopped : Instant.now();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        var time = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return time.withZone(other);
            }

            @Override
            public Instant instant() {
                return time.instant();
            }
        };
    }
}
