package welcomehome;

import org.springframework.boot.SpringApplication;
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
        SpringApplication.run(WelcomeHomeApplication.class, args);
    }
}
