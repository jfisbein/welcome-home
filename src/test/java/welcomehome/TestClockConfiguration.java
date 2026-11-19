package welcomehome;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Gives the app's rate limits a clock that tests move forward. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    @Bean
    TestClock rateLimitClock() {
        return new TestClock();
    }
}
