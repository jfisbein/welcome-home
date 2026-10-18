package welcomehome.auth;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;

/**
 * Owners sign in only with passkeys. The relying party is the host of the public URL, so passkeys
 * registered on one domain do not work on another. Login and invitation registration share the same
 * {@link WebAuthnRelyingPartyOperations}. The admin panel under {@code /admin} is for admins only.
 *
 * <p>CSRF protection is Spring Security's default: every POST needs the token, which pages put in a meta tag
 * for static/js/webauthn.js. On top of Spring Security's default headers, a strict Content Security Policy
 * (only the app's own scripts and styles, nothing inline), no referrer, so that tokens in URLs never leak,
 * and HSTS for a year, sent only over HTTPS. The session cookie is set in application.yaml.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    /** Pico CSS draws its icons with data: SVG images. */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "img-src 'self' data:",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    static final Duration HSTS_MAX_AGE = Duration.ofDays(365);

    /** Only with the web server: the one-off commands run without it, and without HttpSecurity. */
    @Bean
    @ConditionalOnWebApplication
    SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/login", "/invite/**", "/o/**", "/api/open", "/css/**", "/js/**",
                                "/favicon.ico", "/favicon.svg", "/apple-touch-icon.png", "/actuator/health")
                        .permitAll()
                        .requestMatchers("/admin/**").hasRole(OwnerUserDetailsService.ADMIN)
                        .anyRequest().authenticated())
                .webAuthn(webAuthn -> webAuthn.disableDefaultRegistrationPage(true))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(HSTS_MAX_AGE.toSeconds())
                                .includeSubDomains(true)));
        return http.build();
    }

    @Bean
    PublicKeyCredentialRpEntity relyingParty(@Value("${welcomehome.base-url}") String baseUrl) {
        return PublicKeyCredentialRpEntity.builder()
                .id(URI.create(baseUrl).getHost())
                .name("welcome-home")
                .build();
    }

    @Bean
    WebAuthnRelyingPartyOperations relyingPartyOperations(PublicKeyCredentialUserEntityRepository userEntities,
                                                          UserCredentialRepository userCredentials,
                                                          PublicKeyCredentialRpEntity relyingParty,
                                                          @Value("${welcomehome.base-url}") String baseUrl) {
        var publicUrl = URI.create(baseUrl);
        var origin = publicUrl.getScheme() + "://" + publicUrl.getAuthority();
        return new Webauthn4JRelyingPartyOperations(userEntities, userCredentials, relyingParty, Set.of(origin));
    }

    @Bean
    PublicKeyCredentialUserEntityRepository userEntityRepository(JdbcOperations jdbc) {
        return new JdbcPublicKeyCredentialUserEntityRepository(jdbc);
    }

    @Bean
    UserCredentialRepository userCredentialRepository(JdbcOperations jdbc) {
        return new JdbcUserCredentialRepository(jdbc);
    }
}
