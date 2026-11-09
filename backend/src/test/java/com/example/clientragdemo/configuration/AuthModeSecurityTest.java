package com.example.clientragdemo.configuration;

import com.example.clientragdemo.users.application.port.in.ProvisionSsoUserUseCase;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two sign-in modes' filter chains, without a database or any container: which routes are open, what an
 * unauthenticated call gets, CSRF, the consent banner in front of SSO, and the SSO logout. The real
 * identity provider cannot be reached from a test, so the OIDC client registration points at made-up
 * endpoints that are only ever redirected to, never called.
 */
class AuthModeSecurityTest {

    @RestController
    static class ProbeController {
        @GetMapping("/api/probe")
        String probe() {
            return "ok";
        }

        @PostMapping("/api/probe")
        String change() {
            return "changed";
        }
    }

    @WebMvcTest(controllers = {AuthConfigController.class, CsrfController.class, ProbeController.class})
    @Import({SecurityConfig.class, ProbeController.class})
    @TestPropertySource(properties = "app.auth.mode=jdbc")
    @MockitoBean(types = {org.springframework.security.core.userdetails.UserDetailsService.class})
    @Nested
    class Jdbc {

        @Autowired
        MockMvc mvc;

        @Test
        void describesJdbcSignInWithRegistrationOn() throws Exception {
            mvc.perform(get("/api/auth/config").sessionAttr(BannerAckFilter.SESSION_ATTRIBUTE, true))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("jdbc"))
                    .andExpect(jsonPath("$.registrationEnabled").value(true))
                    .andExpect(jsonPath("$.loginUrl").doesNotExist());
        }

        @Test
        void anUnauthenticatedApiCallIs401() throws Exception {
            mvc.perform(get("/api/probe").sessionAttr(BannerAckFilter.SESSION_ATTRIBUTE, true))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void aMutatingRequestWithoutACsrfTokenIs403() throws Exception {
            mvc.perform(post("/api/auth/login").sessionAttr(BannerAckFilter.SESSION_ATTRIBUTE, true))
                    .andExpect(status().isForbidden());
        }

        @Test
        void theCsrfTokenIsHandedOutWithoutAuthenticationOrTheBanner() throws Exception {
            mvc.perform(get("/api/csrf"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                    .andExpect(jsonPath("$.token").isNotEmpty());
        }

        @Test
        void registrationIsOpenToTheAnonymousWhileItIsEnabled() throws Exception {
            // No controller in this slice: getting past security shows as 404 (there is nothing to handle
            // it), being stopped by it shows as 401.
            mvc.perform(post("/api/auth/register").with(csrf()).sessionAttr(BannerAckFilter.SESSION_ATTRIBUTE, true))
                    .andExpect(status().isNotFound());
        }
    }

    @WebMvcTest(controllers = {AuthConfigController.class, CsrfController.class, ProbeController.class})
    @Import({SecurityConfig.class, ProbeController.class})
    @TestPropertySource(properties = {"app.auth.mode=jdbc", "app.auth.registration-enabled=false"})
    @MockitoBean(types = {org.springframework.security.core.userdetails.UserDetailsService.class})
    @Nested
    class JdbcWithRegistrationOff {

        @Autowired
        MockMvc mvc;

        @Test
        void registrationIsNotOffered() throws Exception {
            mvc.perform(get("/api/auth/config").sessionAttr(BannerAckFilter.SESSION_ATTRIBUTE, true))
                    .andExpect(jsonPath("$.registrationEnabled").value(false));
        }

        @Test
        void theRegisterRouteIsNoLongerOpenToTheAnonymous() throws Exception {
            mvc.perform(post("/api/auth/register").with(csrf()).sessionAttr(BannerAckFilter.SESSION_ATTRIBUTE, true))
                    .andExpect(status().isUnauthorized());
        }
    }

    @WebMvcTest(controllers = {AuthConfigController.class, CsrfController.class, ProbeController.class})
    @Import({SecurityConfig.class, ProbeController.class})
    @TestPropertySource(properties = {
            "app.auth.mode=oidc",
            "app.cors.allowed-origins=https://rocky-frontend.example.com",
            "spring.security.oauth2.client.registration.sso.client-id=test-client",
            "spring.security.oauth2.client.registration.sso.client-secret=test-secret",
            "spring.security.oauth2.client.registration.sso.scope=openid,profile,email",
            "spring.security.oauth2.client.registration.sso.authorization-grant-type=authorization_code",
            "spring.security.oauth2.client.registration.sso.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
            "spring.security.oauth2.client.provider.sso.authorization-uri=https://idp.example.com/oauth/authorize",
            "spring.security.oauth2.client.provider.sso.token-uri=https://idp.example.com/oauth/token",
            "spring.security.oauth2.client.provider.sso.jwk-set-uri=https://idp.example.com/token_keys",
            "spring.security.oauth2.client.provider.sso.user-info-uri=https://idp.example.com/userinfo",
            "spring.security.oauth2.client.provider.sso.user-name-attribute=user_name"
    })
    @MockitoBean(types = {ProvisionSsoUserUseCase.class})
    @Nested
    class Oidc {

        @Autowired
        MockMvc mvc;

        @Test
        void describesSsoSignInWithTheLoginUrlAndNoRegistration() throws Exception {
            mvc.perform(get("/api/auth/config").sessionAttr(BannerAckFilter.SESSION_ATTRIBUTE, true))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("oidc"))
                    .andExpect(jsonPath("$.registrationEnabled").value(false))
                    .andExpect(jsonPath("$.loginUrl").value("/oauth2/authorization/sso"));
        }

        @Test
        void anUnauthenticatedApiCallIs401NotARedirectToTheIdentityProvider() throws Exception {
            mvc.perform(get("/api/probe").sessionAttr(BannerAckFilter.SESSION_ATTRIBUTE, true))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void localLoginAndRegistrationAreNotOpen() throws Exception {
            mvc.perform(post("/api/auth/login").with(csrf()).sessionAttr(BannerAckFilter.SESSION_ATTRIBUTE, true))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/auth/register").with(csrf()).sessionAttr(BannerAckFilter.SESSION_ATTRIBUTE, true))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void signInIsRefusedUntilTheConsentBannerIsAcknowledged() throws Exception {
            mvc.perform(get("/oauth2/authorization/sso"))
                    .andExpect(status().isPreconditionRequired());
        }

        @Test
        void signInRedirectsToTheIdentityProviderOnceTheBannerIsAcknowledged() throws Exception {
            mvc.perform(get("/oauth2/authorization/sso").sessionAttr(BannerAckFilter.SESSION_ATTRIBUTE, true))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", startsWith("https://idp.example.com/oauth/authorize")))
                    .andExpect(header().string("Location", containsString("client_id=test-client")));
        }

        @Test
        void aSignedInUserReachesTheApi() throws Exception {
            mvc.perform(get("/api/probe").with(oidcLogin()).sessionAttr(BannerAckFilter.SESSION_ATTRIBUTE, true))
                    .andExpect(status().isOk());
        }

        @Test
        void aMutatingRequestStillNeedsTheCsrfToken() throws Exception {
            mvc.perform(post("/api/probe").with(oidcLogin()).sessionAttr(BannerAckFilter.SESSION_ATTRIBUTE, true))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/probe").with(oidcLogin()).with(csrf()).sessionAttr(BannerAckFilter.SESSION_ATTRIBUTE, true))
                    .andExpect(status().isOk());
        }

        @Test
        void logoutAnswersWithJsonTheFrontendNavigatesTo() throws Exception {
            // No end_session_endpoint is published for this registration, so the handler falls back to the
            // post-logout redirect URI - the frontend. (A real UAA publishes one, and the URL is then that.)
            mvc.perform(post("/api/auth/logout").with(oidcLogin()).with(csrf()).sessionAttr(BannerAckFilter.SESSION_ATTRIBUTE, true))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.logoutUrl").value("https://rocky-frontend.example.com"));
        }
    }
}
