package com.example.clientragdemo.configuration;

import com.example.clientragdemo.TestSessions;
import com.example.clientragdemo.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * The whole application, not a slice, started in {@code app.auth.mode=oidc}: the wiring a slice cannot show -
 * the SSO user service comes up, the jdbc-only controllers are gone, and the real filter chain answers.
 * The client registration points at made-up endpoints that are only ever redirected to.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
        "app.auth.mode=oidc",
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
@AutoConfigureTestRestTemplate
class OidcModeStartupIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ApplicationContext context;

    @LocalServerPort
    private int port;

    @Test
    void theApplicationStartsInOidcModeWithoutTheLocalAccountEndpoints() {
        assertThat(context.getBeanNamesForType(org.springframework.security.oauth2.client.userinfo.OAuth2UserService.class))
                .as("the SSO user service that provisions a profile row on first login").isNotEmpty();
        assertThat(context.containsBean("localLoginController")).isFalse();
        assertThat(context.containsBean("registrationController")).isFalse();
        assertThat(context.containsBean("passwordController")).isFalse();
    }

    @Test
    void theFrontendIsToldToUseSso() {
        HttpHeaders session = TestSessions.acknowledgedSession(restTemplate);

        ResponseEntity<String> response = restTemplate.exchange("/api/auth/config", HttpMethod.GET, new HttpEntity<>(session), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"mode\":\"oidc\"").contains("\"registrationEnabled\":false")
                .contains("\"loginUrl\":\"/oauth2/authorization/sso\"");
    }

    @Test
    void signInRedirectsToTheIdentityProviderOnlyAfterTheBannerIsAcknowledged() throws Exception {
        ResponseEntity<String> beforeAck = restTemplate.getForEntity("/oauth2/authorization/sso", String.class);
        assertThat(beforeAck.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

        // A client that does not follow the redirect: the identity provider here does not exist.
        HttpHeaders session = TestSessions.acknowledgedSession(restTemplate);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/oauth2/authorization/sso"))
                .header(HttpHeaders.COOKIE, session.getFirst(HttpHeaders.COOKIE)).build();
        java.net.http.HttpResponse<String> afterAck = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(afterAck.statusCode()).isEqualTo(302);
        assertThat(afterAck.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).startsWith("https://idp.example.com/oauth/authorize"));
    }

    @Test
    void localSignInAndRegistrationAreNotAvailable() {
        HttpHeaders session = TestSessions.acknowledgedSession(restTemplate);
        HttpEntity<String> body = new HttpEntity<>("{\"username\":\"x\",\"password\":\"password123\"}", withJson(session));

        assertThat(restTemplate.postForEntity("/api/auth/login", body, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.postForEntity("/api/auth/register", body, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange("/api/users/me/password", HttpMethod.PUT, body, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anUnauthenticatedApiCallIs401() {
        HttpHeaders session = TestSessions.acknowledgedSession(restTemplate);

        ResponseEntity<String> response = restTemplate.exchange("/api/auth/me", HttpMethod.GET, new HttpEntity<>(session), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static HttpHeaders withJson(HttpHeaders session) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(session);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }
}
