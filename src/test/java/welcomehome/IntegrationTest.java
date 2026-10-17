package welcomehome;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

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

    /**
     * The web environment: a mock one by default, or a real server for what MockMvc does not show.
     *
     * @return the web environment of the test
     */
    @AliasFor(annotation = SpringBootTest.class)
    WebEnvironment webEnvironment() default WebEnvironment.MOCK;
}
