package welcomehome;

import java.time.Duration;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * After each test, lets the app's clock run again, so that one that set it does not set it for the next, and gives
 * the rate limits back what the test spent, so that tests need no address of their own to start from full ones.
 */
class ResetTestTime implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        var application = SpringExtension.getApplicationContext(context);
        application.getBeanProvider(TestTime.class).ifAvailable(TestTime::reset);
        // And every rate limit full again, as after an hour: what one test spent is not the next one's to find.
        application.getBeanProvider(TestClock.class).ifAvailable(clock -> clock.advance(Duration.ofHours(1)));
    }
}
