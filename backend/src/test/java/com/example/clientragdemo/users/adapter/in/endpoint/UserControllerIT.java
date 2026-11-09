package com.example.clientragdemo.users.adapter.in.endpoint;

import com.example.clientragdemo.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import com.example.clientragdemo.TestSessions;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureTestRestTemplate
class UserControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void updateProfilePersistsAndReturnsUpdatedFields() {
        String username = "profile-" + UUID.randomUUID();
        HttpHeaders authHeaders = registerAndLogin(username);

        ResponseEntity<UserResponse> response = restTemplate.exchange(
                "/api/users/me", HttpMethod.PATCH,
                new HttpEntity<>(new UpdateProfileRequest("Bob", "Baker", "bob@example.com"), authHeaders),
                UserResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().firstName()).isEqualTo("Bob");
        assertThat(response.getBody().lastName()).isEqualTo("Baker");
        assertThat(response.getBody().email()).isEqualTo("bob@example.com");

        ResponseEntity<UserResponse> meResponse = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET, new HttpEntity<>(authHeaders), UserResponse.class);
        assertThat(meResponse.getBody().firstName()).isEqualTo("Bob");
        assertThat(meResponse.getBody().lastName()).isEqualTo("Baker");
        assertThat(meResponse.getBody().email()).isEqualTo("bob@example.com");
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() {
        HttpHeaders authHeaders = registerAndLogin("pwd-wrong-" + UUID.randomUUID());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/users/me/password", HttpMethod.PUT,
                new HttpEntity<>(new ChangePasswordRequest("not-the-real-password", "newpassword123"), authHeaders),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void changePasswordSucceedsAndOldPasswordNoLongerWorks() {
        String username = "pwd-change-" + UUID.randomUUID();
        HttpHeaders authHeaders = registerAndLogin(username);

        ResponseEntity<Void> changeResponse = restTemplate.exchange(
                "/api/users/me/password", HttpMethod.PUT,
                new HttpEntity<>(new ChangePasswordRequest("password123", "newpassword456"), authHeaders),
                Void.class);
        assertThat(changeResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // authHeaders' session already acknowledged the banner (see registerAndLogin) - reusing
        // its Cookie header here is enough for BannerAckFilter, regardless of auth state.
        ResponseEntity<String> oldPasswordLogin = restTemplate.postForEntity(
                "/api/auth/login", new HttpEntity<>(new AuthRequest(username, "password123"), authHeaders), String.class);
        assertThat(oldPasswordLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<UserResponse> newPasswordLogin = restTemplate.postForEntity(
                "/api/auth/login", new HttpEntity<>(new AuthRequest(username, "newpassword456"), authHeaders), UserResponse.class);
        assertThat(newPasswordLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void profileAndPasswordEndpointsRequireAuthentication() {
        // A session with a valid CSRF token but nobody signed in: without the token these would be 403, and
        // this test is about authentication, not CSRF.
        HttpHeaders anonymous = TestSessions.acknowledgedSession(restTemplate);

        ResponseEntity<String> profileResponse = restTemplate.exchange(
                "/api/users/me", HttpMethod.PATCH, new HttpEntity<>(new UpdateProfileRequest("A", "B", "a@b.com"), anonymous), String.class);
        assertThat(profileResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> passwordResponse = restTemplate.exchange(
                "/api/users/me/password", HttpMethod.PUT, new HttpEntity<>(new ChangePasswordRequest("a", "newpassword123"), anonymous), String.class);
        assertThat(passwordResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private HttpHeaders registerAndLogin(String username) {
        // BannerAckFilter gates /api/auth/register and /api/auth/login until this session has
        // acknowledged the consent banner (see SecurityConfig) - ack first and carry that
        // session's cookie through both calls.
        HttpHeaders bannerHeaders = TestSessions.acknowledgedSession(restTemplate);

        AuthRequest credentials = new AuthRequest(username, "password123");
        restTemplate.postForEntity("/api/auth/register", new HttpEntity<>(credentials, bannerHeaders), Void.class);
        ResponseEntity<Void> loginResponse = restTemplate.postForEntity(
                "/api/auth/login", new HttpEntity<>(credentials, bannerHeaders), Void.class);

        // Login doesn't always issue a new Set-Cookie - only when session-fixation protection
        // actually changes the session ID. Reuse the banner-ack cookie in that case.
        return TestSessions.afterLogin(bannerHeaders, loginResponse);
    }

    private record AuthRequest(String username, String password) {}

    private record UpdateProfileRequest(String firstName, String lastName, String email) {}

    private record ChangePasswordRequest(String currentPassword, String newPassword) {}

    private record UserResponse(Long id, String username, String firstName, String lastName, String email) {}
}
