package welcomehome.nuki;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * A stand-in for the Nuki Web API, for integration tests: the application calls it instead of Nuki.
 */
@TestConfiguration(proxyBeanMethods = false)
class NukiStandInConfiguration {

    @Bean(destroyMethod = "stop")
    WireMockServer nukiStandIn() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @Bean
    DynamicPropertyRegistrar nukiProperties(WireMockServer nukiStandIn) {
        return registry -> {
            registry.add("welcomehome.nuki.base-url", nukiStandIn::baseUrl);
            registry.add("welcomehome.nuki.initial-backoff", () -> "10ms");
        };
    }
}
