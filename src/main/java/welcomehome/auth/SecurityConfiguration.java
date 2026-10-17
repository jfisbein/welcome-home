package welcomehome.auth;

import java.net.URI;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
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
 * {@link WebAuthnRelyingPartyOperations}.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/login", "/invite/**", "/css/**", "/js/**", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .webAuthn(webAuthn -> webAuthn.disableDefaultRegistrationPage(true))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"));
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
