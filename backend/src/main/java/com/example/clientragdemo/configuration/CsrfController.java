package com.example.clientragdemo.configuration;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hands the session's CSRF token to the frontend. The usual cookie-based token (read from
 * {@code document.cookie}) cannot work here: the frontend and backend are separate origins on Cloud
 * Foundry, so the frontend cannot read a cookie the backend sets. The browser's same-origin rules still
 * keep another site from calling this and reading the response (CORS only allows the configured
 * origins), which is what makes handing the token out safe. Exempt from the consent-banner gate (see
 * BannerAckFilter) because acknowledging the banner is itself a POST that needs the token.
 */
@RestController
class CsrfController {

    @GetMapping("/api/csrf")
    CsrfTokenResponse csrf(CsrfToken token) {
        return new CsrfTokenResponse(token.getHeaderName(), token.getToken());
    }

    record CsrfTokenResponse(String headerName, String token) {
    }
}
