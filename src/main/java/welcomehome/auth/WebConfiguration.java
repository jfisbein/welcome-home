package welcomehome.auth;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import welcomehome.config.Languages;

@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

    private final OwnerAccountDao owners;

    WebConfiguration(OwnerAccountDao owners) {
        this.owners = owners;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentOwnerArgumentResolver(owners));
    }

    /**
     * The language of each page: the browser's, among those the app has texts for, else the default. Named
     * localeResolver, as Spring MVC looks it up by that name.
     */
    @Bean
    LocaleResolver localeResolver() {
        var resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(Languages.SUPPORTED);
        resolver.setDefaultLocale(Languages.DEFAULT);
        return resolver;
    }
}
