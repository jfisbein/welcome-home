package welcomehome;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import io.github.bucket4j.TimeMeter;

/**
 * The clock of the app's rate limits in tests: the system's, moved forward by what a test asks for, so that a test
 * can see a limit come back without waiting for it. Only ever forward, as the limits are shared by a context's
 * tests: more time passed gives back attempts, which no test minds.
 */
public class TestClock implements TimeMeter {

    private final AtomicLong ahead = new AtomicLong();

    @Override
    public long currentTimeNanos() {
        return System.nanoTime() + ahead.get();
    }

    @Override
    public boolean isWallClockBased() {
        return false;
    }

    /**
     * Lets time pass for the rate limits.
     *
     * @param time how much
     */
    public void advance(Duration time) {
        ahead.addAndGet(time.toNanos());
    }
}
