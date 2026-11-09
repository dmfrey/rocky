package com.example.clientragdemo;

import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a browser does before it can call a mutating endpoint: start a session, fetch that session's CSRF
 * token, and acknowledge the consent banner. {@code TestRestTemplate} keeps no cookies, so the result is a
 * set of headers (the session cookie and the CSRF token header) to send on every request that follows.
 */
public final class TestSessions {

    private TestSessions() {
    }

    /** A fresh session that has fetched its CSRF token and acknowledged the consent banner. */
    @SuppressWarnings("unchecked")
    public static HttpHeaders acknowledgedSession(TestRestTemplate restTemplate) {
        ResponseEntity<Map> csrf = restTemplate.getForEntity("/api/csrf", Map.class);
        assertThat(csrf.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cookie = cookieValue(csrf.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        assertThat(cookie).as("GET /api/csrf starts a session").isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        headers.add((String) csrf.getBody().get("headerName"), (String) csrf.getBody().get("token"));

        ResponseEntity<Void> ack = restTemplate.exchange("/api/banner/ack", HttpMethod.POST, new HttpEntity<>(headers), Void.class);
        assertThat(ack.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        return headers;
    }

    /**
     * The same session after a login response: login does not always issue a new Set-Cookie (only when
     * session-fixation protection changes the session id), so keep the current cookie unless it did. The
     * CSRF token header is kept either way.
     */
    public static HttpHeaders afterLogin(HttpHeaders session, ResponseEntity<?> loginResponse) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(session);
        String loginCookie = cookieValue(loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        if (loginCookie != null) {
            headers.set(HttpHeaders.COOKIE, loginCookie);
        }
        return headers;
    }

    private static String cookieValue(String setCookie) {
        return setCookie == null ? null : setCookie.split(";", 2)[0];
    }
}
