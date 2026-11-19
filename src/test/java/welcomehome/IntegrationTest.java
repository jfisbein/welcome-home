package welcomehome;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

/**
 * A Spring Boot test with the test infrastructure: Postgres in a container, freshly generated keysets, a clock that
 * tests set ({@link TestTime}), one for the rate limits that they move forward ({@link TestClock}), and no
 * scheduled job running by itself.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "welcomehome.base-url=" + IntegrationTest.BASE_URL,
        // No job runs by the clock while tests do: a run that crossed 08:15, 03:30 or minute 17 of an hour had the
        // sync, a purge or the retry of connections act on the tests' database and their Nuki. Tests call the jobs.
        "welcomehome.nuki.recheck-cron=-",
        "welcomehome.locks.sync-cron=-",
        "welcomehome.links.purge-cron=-",
        "welcomehome.audit.purge-cron=-",
        "welcomehome.admin.purge-cron=-"})
@ExtendWith(ResetTestTime.class)
@Import({TestDatabaseConfiguration.class, TestKeysetsConfiguration.class, TestClockConfiguration.class})
public @interface IntegrationTest {

    /** The public URL of the app in tests. */
    String BASE_URL = "https://welcome.example";

    /**
     * The web environment: a mock one by default, or a real server for what MockMvc does not show.
     *
     * @return the web environment of the test
     */
    @AliasFor(annotation = SpringBootTest.class)
    WebEnvironment webEnvironment() default WebEnvironment.MOCK;
}
