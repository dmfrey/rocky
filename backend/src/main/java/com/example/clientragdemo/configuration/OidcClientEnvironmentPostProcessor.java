package com.example.clientragdemo.configuration;

import io.pivotal.cfenv.core.CfCredentials;
import io.pivotal.cfenv.core.CfEnv;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In {@code app.auth.mode=oidc}, builds the Spring Security OAuth2 client registration so the deployment only
 * has to say where its identity provider is. The settings come from, highest precedence first:
 *
 * <ol>
 *   <li>an explicit {@code spring.security.oauth2.client.registration.<id>.client-id} - then this does nothing,
 *       and anything Spring Security supports can be configured by hand;</li>
 *   <li>the plain settings {@code app.auth.oidc.issuer-uri}, {@code client-id}, {@code client-secret} (and
 *       {@code username-attribute}) - what a Kubernetes or any other environment sets, as
 *       {@code APP_AUTH_OIDC_*} variables;</li>
 *   <li>on Cloud Foundry, the bound Tanzu SSO service ({@code p-identity}; {@code rocky-sso} by default -
 *       {@code app.auth.oidc.service}) read with java-cfenv: {@code client_id}, {@code client_secret}, and the
 *       issuer taken from the discovery document served at the binding's {@code auth_domain}. It is read from
 *       there, not built from {@code auth_domain}, because they differ: on this platform {@code auth_domain} is
 *       on the {@code login.} host while the issuer UAA reports is on the {@code uaa.} host, and Spring Security
 *       refuses a registration whose requested issuer is not the one the provider reports. (Falls back to
 *       {@code <auth_domain>/oauth/token} when the document cannot be fetched, so startup then fails with
 *       Spring's own issuer-mismatch message.)</li>
 * </ol>
 *
 * The identity provider only has to be OpenID Connect compliant (UAA, and so the SSO tile, is) - the issuer is
 * resolved through its discovery document. Values are added as the lowest-precedence property source, so any of
 * them can still be overridden one by one.
 */
public class OidcClientEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "oidcClient";

    private static final Pattern ISSUER = Pattern.compile("\"issuer\"\\s*:\\s*\"([^\"]+)\"");

    private final Supplier<CfEnv> cfEnv;
    private final Function<String, String> issuerLookup;

    public OidcClientEnvironmentPostProcessor() {
        this(CfEnv::new, OidcClientEnvironmentPostProcessor::discoverIssuer);
    }

    OidcClientEnvironmentPostProcessor(Supplier<CfEnv> cfEnv, Function<String, String> issuerLookup) {
        this.cfEnv = cfEnv;
        this.issuerLookup = issuerLookup;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!"oidc".equalsIgnoreCase(environment.getProperty("app.auth.mode"))) {
            return;
        }
        String registrationId = environment.getProperty("app.auth.oidc.registration-id", "sso");
        String registration = "spring.security.oauth2.client.registration." + registrationId;
        if (environment.containsProperty(registration + ".client-id")) {
            return;
        }
        CfCredentials binding = binding(environment.getProperty("app.auth.oidc.service", "rocky-sso"));

        String clientId = firstNonBlank(environment.getProperty("app.auth.oidc.client-id"), binding == null ? null : binding.getString("client_id"));
        String clientSecret = firstNonBlank(environment.getProperty("app.auth.oidc.client-secret"), binding == null ? null : binding.getString("client_secret"));
        String authDomain = binding == null ? null : binding.getString("auth_domain");
        String issuerUri = environment.getProperty("app.auth.oidc.issuer-uri");
        if ((issuerUri == null || issuerUri.isBlank()) && authDomain != null) {
            String base = authDomain.replaceAll("/+$", "");
            issuerUri = firstNonBlank(issuerLookup.apply(base), base + "/oauth/token");
        }
        if (clientId == null || clientSecret == null) {
            return; // SecurityConfig fails startup with a message naming what is missing
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(registration + ".client-id", clientId);
        properties.put(registration + ".client-secret", clientSecret);
        properties.put(registration + ".scope", "openid,profile,email");
        properties.put(registration + ".authorization-grant-type", "authorization_code");
        // Spring Boot defaults this only for the well-known providers (Google, GitHub, ...); a registration
        // built from an issuer must say it, or startup fails with "redirectUri cannot be empty". The URL
        // is the one the identity provider must be told about (see offline/enable-sso.sh).
        properties.put(registration + ".redirect-uri", "{baseUrl}/login/oauth2/code/{registrationId}");
        if (issuerUri != null) {
            properties.put("spring.security.oauth2.client.provider." + registrationId + ".issuer-uri", issuerUri);
        }
        // The username is what chat sessions and documents are owned by; the default (sub) is an opaque id.
        properties.put("spring.security.oauth2.client.provider." + registrationId + ".user-name-attribute",
                environment.getProperty("app.auth.oidc.username-attribute", "user_name"));
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
    }

    private CfCredentials binding(String service) {
        try {
            return cfEnv.get().findServiceByName(service).getCredentials();
        } catch (RuntimeException e) {
            return null; // not on Cloud Foundry, or no such binding
        }
    }

    /** The {@code issuer} the provider reports at {@code <authDomain>/.well-known/openid-configuration}, or null. */
    static String discoverIssuer(String authDomain) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(authDomain + "/.well-known/openid-configuration"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            Matcher matcher = ISSUER.matcher(response.body());
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    @Override
    public int getOrder() {
        // After config data is loaded, so explicit settings in application*.yaml / env vars are seen.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
