package com.example.clientragdemo.configuration;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the frontend how to sign in, so it never hardcodes the mode or the client registration id.
 * Reachable before authentication (SecurityConfig permits it) but, like every other endpoint, only once
 * the consent banner has been acknowledged.
 */
@RestController
class AuthConfigController {

    private final AuthProperties properties;

    AuthConfigController(AuthProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/auth/config")
    AuthConfig config() {
        String loginUrl = properties.oidcMode() ? "/oauth2/authorization/" + properties.oidc().registrationId() : null;
        return new AuthConfig(properties.mode().name().toLowerCase(), properties.registrationAvailable(), loginUrl);
    }

    record AuthConfig(String mode, boolean registrationEnabled, String loginUrl) {
    }
}
