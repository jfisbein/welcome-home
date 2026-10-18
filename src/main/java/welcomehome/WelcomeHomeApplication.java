package welcomehome;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of welcome-home. Scheduling runs the daily jobs, such as the Nuki connection check; async
 * execution runs the event listeners that send emails, away from the request that published the event.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class WelcomeHomeApplication {

    WelcomeHomeApplication() {
    }

    /**
     * Starts the application.
     *
     * @param args command-line arguments, passed on to Spring Boot
     */
    public static void main(String[] args) {
        var application = new SpringApplication(WelcomeHomeApplication.class);
        if (runsACommand(args)) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        application.run(args);
    }

    /**
     * Whether the arguments ask for one of the one-off commands, which run without the web server:
     * {@code --bootstrap-admin} and {@code --recover-owner=<email>} (see admin.BootstrapAdmin and
     * admin.PasskeyRecoveryCommand).
     */
    static boolean runsACommand(String... args) {
        return Arrays.stream(args).anyMatch(arg -> arg.equals("--bootstrap-admin") || arg.startsWith("--recover-owner"));
    }
}
