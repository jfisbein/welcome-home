package welcomehome.auth;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpMessageConverterAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import welcomehome.config.WelcomeHomeProperties;

/**
 * Owners sign in only with passkeys. The relying party is the host of the public URL, so passkeys
 * registered on one domain do not work on another. Login and invitation registration share the same
 * {@link WebAuthnRelyingPartyOperations}. The admin panel under {@code /admin} is for admins only.
 *
 * <p>CSRF protection is Spring Security's default: every POST needs the token, which pages put in a meta tag
 * for static/js/webauthn.js, and signing in renews it with the session's id. The exceptions are {@code POST /api/open}: the link's token in the body is the
 * guest's only credential, which a forged request would need anyway, and a token kept in the session went stale
 * when a guest opened from a page opened more than a session's lifetime earlier; and {@code POST /nuki/webhook},
 * whose credential is Nuki's signature of the body. On top of Spring Security's
 * default headers, a strict Content Security Policy (only the app's own scripts and styles, nothing inline), no
 * referrer, so that tokens in URLs never leak, and HSTS for a year, sent only over HTTPS. The session cookie is
 * set in application.yaml.
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

    /** Where CSRF tokens are kept: the session, as by default; named so that signing in can renew them. */
    static final CsrfTokenRepository CSRF_TOKENS = new HttpSessionCsrfTokenRepository();

    /** Passkeys must verify their user and be discoverable, however they are registered. */
    static final AuthenticatorSelectionCriteria PASSKEYS = AuthenticatorSelectionCriteria.builder()
            .userVerification(UserVerificationRequirement.REQUIRED)
            .residentKey(ResidentKeyRequirement.REQUIRED)
            .build();

    /**
     * What signing in does to the session, by a passkey or by an invitation: a new session id and a new CSRF token.
     * Someone who planted a session in the browser beforehand, as a page on a sibling domain can with a cookie for
     * the parent domain, knows both, and would otherwise share the session or forge the owner's forms.
     */
    static final SessionAuthenticationStrategy ON_SIGN_IN = new CompositeSessionAuthenticationStrategy(List.of(
            new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(CSRF_TOKENS)));

    /** Only with the web server: the one-off commands run without it, and without HttpSecurity. */
    @Bean
    @ConditionalOnWebApplication
    SecurityFilterChain securityFilterChain(HttpSecurity http, OwnerSessions sessions, OwnerAccountDao owners,
                                           PasskeyDao passkeys, ApplicationEventPublisher events) {
        http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/login", "/invite/**", "/email/verify/**", "/o/**", "/api/open",
                                "/nuki/webhook", "/css/**", "/js/**",
                                "/favicon.ico", "/favicon.svg", "/apple-touch-icon.png", "/actuator/health",
                                // The error page, for a guest's error on a public route; an unknown URL still
                                // sends a visitor who is not signed in to /login, so no route shows it exists.
                                "/error")
                        .permitAll()
                        .requestMatchers("/admin/**").hasRole(OwnerUserDetailsService.ADMIN)
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.csrfTokenRepository(CSRF_TOKENS)
                        .ignoringRequestMatchers("/api/open", "/nuki/webhook"))
                .webAuthn(webAuthn -> webAuthn.disableDefaultRegistrationPage(true))
                .addFilterAfter(new OwnerSessionTrackingFilter(sessions), SecurityContextHolderFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"))
                        // Also what a CSRF failure gets: a form from a page whose session ended goes to sign in.
                        .accessDeniedHandler(new EndedSessionFormHandler()))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(HSTS_MAX_AGE.toSeconds())
                                .includeSubDomains(true)));
        var chain = http.build();
        // WebAuthnConfigurer leaves its filter without a session strategy, so a sign-in would keep the session id
        // and the CSRF token from before it (ON_SIGN_IN). And each sign-in is announced, for the owner's activity,
        // before the filter's own answer.
        chain.getFilters().stream()
                .filter(WebAuthnAuthenticationFilter.class::isInstance)
                .map(WebAuthnAuthenticationFilter.class::cast)
                .forEach(filter -> {
                    filter.setSessionAuthenticationStrategy(ON_SIGN_IN);
                    filter.setAuthenticationSuccessHandler(new SignInAnnouncer(
                            new HttpMessageConverterAuthenticationSuccessHandler(), owners, passkeys, events));
                });
        return chain;
    }

    @Bean
    PublicKeyCredentialRpEntity relyingParty(WelcomeHomeProperties app) {
        return PublicKeyCredentialRpEntity.builder()
                .id(app.host())
                .name("welcome-home")
                .build();
    }

    @Bean
    WebAuthnRelyingPartyOperations relyingPartyOperations(PublicKeyCredentialUserEntityRepository userEntities,
                                                          UserCredentialRepository userCredentials,
                                                          PublicKeyCredentialRpEntity relyingParty,
                                                          WelcomeHomeProperties app) {
        var operations = new Webauthn4JRelyingPartyOperations(userEntities, userCredentials, relyingParty,
                Set.of(app.origin()));
        // A passkey is the only factor, so it must verify its user (PIN, fingerprint or face): a security key that
        // anyone holding it can touch is not enough for an account that opens doors. Asked for, and checked when
        // the answer comes back.
        operations.setCustomizeCreationOptions(options -> options.authenticatorSelection(PASSKEYS));
        operations.setCustomizeRequestOptions(options -> options.userVerification(UserVerificationRequirement.REQUIRED));
        return operations;
    }

    @Bean
    PublicKeyCredentialUserEntityRepository userEntityRepository(JdbcOperations jdbc) {
        return new JdbcPublicKeyCredentialUserEntityRepository(jdbc);
    }

    @Bean
    UserCredentialRepository userCredentialRepository(JdbcOperations jdbc,
                                                      PublicKeyCredentialUserEntityRepository userEntities,
                                                      ApplicationEventPublisher events) {
        return new AnnouncingUserCredentialRepository(new JdbcUserCredentialRepository(jdbc), userEntities, events);
    }
}
