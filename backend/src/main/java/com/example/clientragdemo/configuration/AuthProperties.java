package com.example.clientragdemo.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How users sign in, chosen per deployment with {@code APP_AUTH_MODE}: {@code jdbc} (the default - local
 * username/password accounts in the {@code users} table) or {@code oidc} (single sign-on against an
 * OpenID Connect provider, normally the Tanzu SSO tile's service binding). Exactly one is active; they are
 * never offered side by side.
 *
 * @param mode                the sign-in mechanism
 * @param registrationEnabled whether anyone who can reach the app may create a local account
 *                            ({@code jdbc} mode only - in {@code oidc} mode there is no local registration)
 * @param oidc                settings that only apply in {@code oidc} mode
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        @DefaultValue("jdbc") Mode mode,
        @DefaultValue("true") boolean registrationEnabled,
        @DefaultValue Oidc oidc) {

    public enum Mode {JDBC, OIDC}

    /**
     * <p>The client registration is built (see {@code OidcClientEnvironmentPostProcessor}) from, in order of
     * precedence: an explicit {@code spring.security.oauth2.client.*} registration (anything Spring Security
     * supports), then these plain settings ({@code issuerUri}, {@code clientId}, {@code clientSecret} - what a
     * Kubernetes deployment sets), then the bound Tanzu SSO service on Cloud Foundry.
     *
     * @param registrationId    the Spring Security client registration to sign in with
     * @param service           name of the bound Tanzu SSO service instance (Cloud Foundry) to read credentials from
     * @param issuerUri         the OpenID Connect issuer; overrides the one derived from the binding's {@code auth_domain}
     * @param clientId          overrides the binding's {@code client_id}; required without a binding
     * @param clientSecret      overrides the binding's {@code client_secret}; required without a binding
     * @param usernameAttribute the ID-token/userinfo claim that is the username (the key chats and documents are
     *                          owned by); {@code user_name} is UAA's, a generic provider often wants
     *                          {@code preferred_username}
     * @param successUrl        where the browser lands after sign-in/sign-out; defaults to the first
     *                          {@code app.cors.allowed-origins} entry (the frontend)
     */
    public record Oidc(
            @DefaultValue("sso") String registrationId,
            @DefaultValue("rocky-sso") String service,
            String issuerUri,
            String clientId,
            String clientSecret,
            @DefaultValue("user_name") String usernameAttribute,
            String successUrl) {
    }

    public boolean oidcMode() {
        return mode == Mode.OIDC;
    }

    /** Local registration exists only in jdbc mode, and only while switched on. */
    public boolean registrationAvailable() {
        return mode == Mode.JDBC && registrationEnabled;
    }
}
