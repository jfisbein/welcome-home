package welcomehome.db;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code --migrate} command: brings the database's schema up to this release's and exits, with nothing of the
 * application started. Production's stack runs it as a service of its own before the application, which then
 * starts with {@code spring.liquibase.enabled=false} and only its own role's password: the role that migrates
 * owns every table, and Row-Level Security does not hold it back, so its password is kept out of the container
 * that serves requests. Development and the tests leave Liquibase on, and the application migrates as it starts.
 *
 * <p>It takes {@code spring.liquibase.url}, {@code user} and {@code password}, and needs nothing else: no keyset,
 * no public URL, no datasource of the application's.
 */
public final class Migration {

    /** The argument that asks for it. */
    public static final String OPTION = "--migrate";

    private Migration() {
    }

    /**
     * Tells whether the arguments ask to migrate and exit.
     *
     * @param args the command-line arguments
     * @return whether {@link #OPTION} is among them
     */
    public static boolean isAskedFor(String... args) {
        return Arrays.asList(args).contains(OPTION);
    }

    /**
     * Migrates the database and returns once it is done: Liquibase runs as the context starts, and the context is
     * then closed. A changeset that fails throws, so that the command ends in an error and the application is not
     * started against a schema it does not know.
     *
     * @param args the command-line arguments, passed on to Spring Boot
     */
    public static void run(String... args) {
        var application = new SpringApplication(Only.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args).close();
    }

    /** Liquibase and nothing else, with the application's properties. */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(LiquibaseAutoConfiguration.class)
    static class Only {
    }
}
