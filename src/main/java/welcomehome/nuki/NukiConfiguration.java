package welcomehome.nuki;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NukiProperties.class)
class NukiConfiguration {

    @Bean
    NukiRequests nukiRequests(NukiProperties properties) {
        return new NukiRequests(properties);
    }

    @Bean
    NukiWebClient nukiWebClient(NukiRequests requests) {
        return new NukiWebClient(requests);
    }

    @Bean
    NukiOAuthClient nukiOAuthClient(NukiRequests requests, NukiProperties properties) {
        return new NukiOAuthClient(requests, properties);
    }
}
