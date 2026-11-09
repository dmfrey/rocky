package com.example.clientragdemo.configuration;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Two mutually exclusive filter chains, chosen by {@code app.auth.mode} ({@link AuthProperties}): local
 * username/password accounts ({@code jdbc}, the default) or OpenID Connect single sign-on ({@code oidc}).
 * Everything else - the session, CORS, CSRF, the consent banner, the 401 for an unauthenticated API call -
 * is shared and identical in both.
 *
 * <p><b>CSRF protection is on.</b> The session cookie is {@code SameSite=None} on Cloud Foundry (the
 * frontend and backend are separate origins), so a browser attaches it to a request any other site
 * triggers. CORS does not stop that for a "simple" request such as an HTML form post - and the document
 * upload is a {@code multipart/form-data} post, which a form can send. The token is kept in the session
 * and handed to the frontend by {@link CsrfController}; the frontend sends it in a header on every
 * mutating request.
 */
@Configuration
@EnableConfigurationProperties({BannerProperties.class, AuthProperties.class})
class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "app.auth", name = "mode", havingValue = "jdbc", matchIfMissing = true)
    SecurityFilterChain jdbcSecurityFilterChain(HttpSecurity http,
                                                CorsConfigurationSource corsConfigurationSource,
                                                SecurityContextRepository securityContextRepository,
                                                AuthProperties authProperties) throws Exception {
        common(http, corsConfigurationSource, securityContextRepository);
        http
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(HttpMethod.GET, "/api/banner", "/api/csrf", "/api/auth/config").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/banner/ack", "/api/auth/login").permitAll()
                            .requestMatchers(EndpointRequest.to("health", "prometheus")).permitAll();
                    if (authProperties.registrationAvailable()) {
                        authorize.requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll();
                    }
                    authorize.anyRequest().authenticated();
                })
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(HttpServletResponse.SC_NO_CONTENT)));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.auth", name = "mode", havingValue = "oidc")
    SecurityFilterChain oidcSecurityFilterChain(HttpSecurity http,
                                                CorsConfigurationSource corsConfigurationSource,
                                                SecurityContextRepository securityContextRepository,
                                                AuthProperties authProperties,
                                                ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                                                @Value("${app.cors.allowed-origins:http://localhost:5173}") String[] allowedOrigins) throws Exception {
        ClientRegistrationRepository registrations = clientRegistrations.getIfAvailable();
        if (registrations == null) {
            throw new IllegalStateException("app.auth.mode=oidc but no OAuth2 client registration is configured. Either bind "
                    + "the Tanzu SSO service instance '" + authProperties.oidc().service() + "' to this app (Cloud Foundry; name it "
                    + "with APP_AUTH_OIDC_SERVICE), or set APP_AUTH_OIDC_ISSUER_URI, APP_AUTH_OIDC_CLIENT_ID and "
                    + "APP_AUTH_OIDC_CLIENT_SECRET (any other environment), or configure spring.security.oauth2.client.registration."
                    + authProperties.oidc().registrationId() + ".* yourself.");
        }
        String frontendUrl = authProperties.oidc().successUrl() != null
                ? authProperties.oidc().successUrl() : allowedOrigins[0];

        // Responds 200 with {"logoutUrl"} rather than a 302: the SPA signs out with fetch(), which would
        // follow a redirect itself (cross-origin, without cookies) and leave the identity provider's
        // session untouched. The SPA navigates to the URL instead.
        JsonOidcLogoutSuccessHandler logoutSuccessHandler = new JsonOidcLogoutSuccessHandler(registrations);
        logoutSuccessHandler.setPostLogoutRedirectUri(frontendUrl);
        // Used when the provider publishes no end_session_endpoint: without it the fallback is "/", which on
        // the backend's own origin is not the frontend.
        logoutSuccessHandler.setDefaultTargetUrl(frontendUrl);

        common(http, corsConfigurationSource, securityContextRepository);
        http
                .addFilterBefore(new OAuth2LoginBannerGate(), OAuth2AuthorizationRequestRedirectFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/banner", "/api/csrf", "/api/auth/config").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/banner/ack").permitAll()
                        .requestMatchers("/oauth2/**", "/login/**").permitAll()
                        .requestMatchers(EndpointRequest.to("health", "prometheus")).permitAll()
                        // Any signed-in SSO user: there are no other roles.
                        .anyRequest().authenticated())
                .oauth2Login(login -> login
                        .defaultSuccessUrl(frontendUrl, true)
                        .failureHandler(new LoggingLoginFailureHandler(frontendUrl + "/?login_error")))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(logoutSuccessHandler));
        return http.build();
    }

    /** Session, CORS, CSRF and the 401 an unauthenticated API call gets - the same in both modes. */
    private static void common(HttpSecurity http, CorsConfigurationSource corsConfigurationSource,
                               SecurityContextRepository securityContextRepository) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(Customizer.withDefaults())
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                // No replay of the request that triggered the sign-in: the frontend's first call is an XHR to
                // /api/auth/me, and replaying that after login would dump raw JSON at the user.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                        // Written directly, not with sendError: that forwards to /error, which re-enters this chain
                        // unauthenticated and gets its 403 overwritten with a 401 - found on a real request: a CSRF
                        // failure on /api/auth/login (no session yet) came back 401, which the frontend would not
                        // recognise as "refetch the token and retry".
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"error\":\"Forbidden\"}");
                        }));
    }

    @Bean
    BannerController bannerController(BannerProperties properties) {
        return new BannerController(properties);
    }

    // Default FilterRegistrationBean order (Ordered.LOWEST_PRECEDENCE) runs this after Spring
    // Security's own filter chain - see BannerAckFilter's own comment for why that ordering is
    // exactly what this app needs.
    @Bean
    FilterRegistrationBean<BannerAckFilter> bannerAckFilterRegistration(BannerProperties properties) {
        FilterRegistrationBean<BannerAckFilter> registration = new FilterRegistrationBean<>(new BannerAckFilter(properties));
        registration.setName("bannerAckFilter");
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins:http://localhost:5173}") String[] allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * A failed sign-in is logged with its whole cause chain (an OAuth2 exception says only that the token
     * request failed, not why) and sends the browser back to the frontend with a flag it can show, rather
     * than to a {@code /login?error} page this backend does not serve.
     */
    private static final class LoggingLoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

        LoggingLoginFailureHandler(String failureUrl) {
            super(failureUrl);
        }

        @Override
        public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                            AuthenticationException exception) throws IOException, ServletException {
            log.error("SSO sign-in failed: {}", causeChain(exception), exception);
            super.onAuthenticationFailure(request, response, exception);
        }

        private static String causeChain(Throwable throwable) {
            StringBuilder chain = new StringBuilder();
            for (Throwable cause = throwable; cause != null && chain.length() < 1_000; cause = cause.getCause()) {
                if (!chain.isEmpty()) {
                    chain.append(" <- ");
                }
                chain.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
                if (cause.getCause() == cause) {
                    break;
                }
            }
            return chain.toString();
        }
    }

    /** RP-initiated logout whose target is returned as JSON for the SPA to navigate to. */
    private static final class JsonOidcLogoutSuccessHandler extends OidcClientInitiatedLogoutSuccessHandler {

        JsonOidcLogoutSuccessHandler(ClientRegistrationRepository registrations) {
            super(registrations);
        }

        @Override
        public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
                                    Authentication authentication) throws IOException {
            String logoutUrl = determineTargetUrl(request, response, authentication);
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            // Written by hand: Boot 4 serializes with Jackson 3, and this one string needs no mapper.
            response.getWriter().write("{\"logoutUrl\":\"" + logoutUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        }
    }
}
