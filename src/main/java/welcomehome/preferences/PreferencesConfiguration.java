package welcomehome.preferences;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;

@Configuration(proxyBeanMethods = false)
class PreferencesConfiguration {

    /** Named localeResolver, as Spring MVC looks it up by that name. */
    @Bean
    LocaleResolver localeResolver(OwnerLanguageDao owners) {
        return new OwnerLanguageResolver(owners);
    }
}
