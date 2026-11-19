package welcomehome.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock the app reads the time from, wherever what it does depends on what time it is: a link's state, its
 * schedule, what a purge deletes, when a token expires. A bean, so that tests set it. How long something takes, such
 * as the wait for a lock to confirm an opening, is measured with the real clock instead; and so is what is compared
 * with the database's {@code now()} or with Nuki's own times.
 */
@Configuration(proxyBeanMethods = false)
class TimeConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
