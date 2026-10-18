package welcomehome.notifications;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * An SMTP server stand-in for integration tests: the application sends its emails there.
 * Test classes that import it with the same other annotations share one application context. They also set
 * {@code spring.mail.host=127.0.0.1} as a test property: Spring Boot decides whether to define a mail sender
 * from it before the properties registered here exist.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MailStandInConfiguration {

    static final String FROM = "welcome-home <avisos@welcome.example>";

    @Bean(destroyMethod = "stop")
    GreenMail mailStandIn() {
        var server = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
        server.start();
        return server;
    }

    @Bean
    DynamicPropertyRegistrar mailProperties(GreenMail mailStandIn) {
        return registry -> {
            registry.add("spring.mail.port", () -> mailStandIn.getSmtp().getPort());
            // The stand-in speaks plain SMTP; production requires STARTTLS.
            registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
            registry.add("spring.mail.properties.mail.smtp.starttls.required", () -> "false");
            registry.add("welcomehome.notifications.from", () -> FROM);
        };
    }
}
