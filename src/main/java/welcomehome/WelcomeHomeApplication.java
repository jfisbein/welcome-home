package welcomehome;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of welcome-home.
 */
@SpringBootApplication
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
        // The one-off --bootstrap-admin command (see admin.BootstrapAdmin) runs without the web server.
        if (Arrays.asList(args).contains("--bootstrap-admin")) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        application.run(args);
    }
}
