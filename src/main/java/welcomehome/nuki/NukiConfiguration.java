package welcomehome.nuki;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NukiProperties.class)
class NukiConfiguration {

    @Bean
    NukiWebClient nukiWebClient(NukiProperties properties) {
        return new NukiWebClient(properties);
    }
}
