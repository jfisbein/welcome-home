package welcomehome;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * A Spring Boot test with the test infrastructure: Postgres in a container and freshly generated keysets.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "welcomehome.base-url=" + IntegrationTest.BASE_URL)
@Import({TestDatabaseConfiguration.class, TestKeysetsConfiguration.class})
public @interface IntegrationTest {

    /** The public URL of the app in tests. */
    String BASE_URL = "https://welcome.example";
}
