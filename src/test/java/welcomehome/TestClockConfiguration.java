package welcomehome;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Gives the app a clock that tests set, and its rate limits one that tests move forward. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    @Bean
    @Primary
    TestTime time() {
        return new TestTime();
    }

    @Bean
    TestClock rateLimitClock() {
        return new TestClock();
    }
}
