package com.example.clientragdemo.configuration;

import io.pivotal.cfenv.core.CfEnv;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class OidcClientEnvironmentPostProcessorTest {

    private static final String VCAP_SERVICES = """
            {"p-identity":[{"name":"rocky-sso","label":"p-identity","plan":"kuhn-labs-sso","tags":[],
              "credentials":{"client_id":"the-client","client_secret":"the-secret",
                             "auth_domain":"https://rocky.login.sys.example.com"}}],
             "postgres":[{"name":"rocky-db","label":"postgres","credentials":{}}]}""";

    private static OidcClientEnvironmentPostProcessor processor(String vcapServices) {
        return new OidcClientEnvironmentPostProcessor(() -> new CfEnv("{}", vcapServices));
    }

    private static StandardEnvironment environment(String... pairs) {
        StandardEnvironment environment = new StandardEnvironment();
        MockPropertySource source = new MockPropertySource("test");
        for (int i = 0; i < pairs.length; i += 2) {
            source.setProperty(pairs[i], pairs[i + 1]);
        }
        environment.getPropertySources().addFirst(source);
        return environment;
    }

    @Test
    void buildsTheClientRegistrationFromTheBoundSsoService() {
        StandardEnvironment environment = environment("app.auth.mode", "oidc");

        processor(VCAP_SERVICES).postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.security.oauth2.client.registration.sso.client-id")).isEqualTo("the-client");
        assertThat(environment.getProperty("spring.security.oauth2.client.registration.sso.client-secret")).isEqualTo("the-secret");
        assertThat(environment.getProperty("spring.security.oauth2.client.registration.sso.scope")).isEqualTo("openid,profile,email");
        assertThat(environment.getProperty("spring.security.oauth2.client.registration.sso.redirect-uri"))
                .isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
        assertThat(environment.getProperty("spring.security.oauth2.client.provider.sso.issuer-uri"))
                .isEqualTo("https://rocky.login.sys.example.com/oauth/token");
        assertThat(environment.getProperty("spring.security.oauth2.client.provider.sso.user-name-attribute")).isEqualTo("user_name");
    }

    @Test
    void doesNothingOutsideOidcMode() {
        StandardEnvironment environment = environment();

        processor(VCAP_SERVICES).postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.security.oauth2.client.registration.sso.client-id")).isNull();
    }

    @Test
    void anExplicitRegistrationAlwaysWins() {
        StandardEnvironment environment = environment("app.auth.mode", "oidc",
                "spring.security.oauth2.client.registration.sso.client-id", "explicit");

        processor(VCAP_SERVICES).postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.security.oauth2.client.registration.sso.client-id")).isEqualTo("explicit");
        assertThat(environment.getProperty("spring.security.oauth2.client.registration.sso.client-secret")).isNull();
    }

    @Test
    void usesTheServiceAndRegistrationIdNamedInConfiguration() {
        StandardEnvironment environment = environment("app.auth.mode", "oidc",
                "app.auth.oidc.service", "my-sso", "app.auth.oidc.registration-id", "corp");
        String renamed = VCAP_SERVICES.replace("rocky-sso", "my-sso");

        processor(renamed).postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.security.oauth2.client.registration.corp.client-id")).isEqualTo("the-client");
    }

    @Test
    void theIssuerCanBeOverridden() {
        StandardEnvironment environment = environment("app.auth.mode", "oidc", "app.auth.oidc.issuer-uri", "https://issuer.example.com");

        processor(VCAP_SERVICES).postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.security.oauth2.client.provider.sso.issuer-uri")).isEqualTo("https://issuer.example.com");
    }

    @Test
    void leavesTheRegistrationUnsetWhenTheServiceIsNotBound() {
        StandardEnvironment environment = environment("app.auth.mode", "oidc");

        processor("{}").postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.security.oauth2.client.registration.sso.client-id")).isNull();
    }

    @Test
    void buildsTheRegistrationFromPlainSettingsWithoutAnyBinding() {
        // What a Kubernetes deployment sets: APP_AUTH_OIDC_ISSUER_URI / _CLIENT_ID / _CLIENT_SECRET.
        StandardEnvironment environment = environment("app.auth.mode", "oidc",
                "app.auth.oidc.issuer-uri", "https://idp.example.com/realms/rocky",
                "app.auth.oidc.client-id", "k8s-client", "app.auth.oidc.client-secret", "k8s-secret",
                "app.auth.oidc.username-attribute", "preferred_username");

        processor("{}").postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.security.oauth2.client.registration.sso.client-id")).isEqualTo("k8s-client");
        assertThat(environment.getProperty("spring.security.oauth2.client.registration.sso.client-secret")).isEqualTo("k8s-secret");
        assertThat(environment.getProperty("spring.security.oauth2.client.provider.sso.issuer-uri")).isEqualTo("https://idp.example.com/realms/rocky");
        assertThat(environment.getProperty("spring.security.oauth2.client.provider.sso.user-name-attribute")).isEqualTo("preferred_username");
    }

    @Test
    void plainSettingsOverrideTheBinding() {
        StandardEnvironment environment = environment("app.auth.mode", "oidc",
                "app.auth.oidc.client-id", "explicit-client", "app.auth.oidc.issuer-uri", "https://issuer.example.com");

        processor(VCAP_SERVICES).postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.security.oauth2.client.registration.sso.client-id")).isEqualTo("explicit-client");
        // ...while the parts that were not overridden still come from the binding.
        assertThat(environment.getProperty("spring.security.oauth2.client.registration.sso.client-secret")).isEqualTo("the-secret");
        assertThat(environment.getProperty("spring.security.oauth2.client.provider.sso.issuer-uri")).isEqualTo("https://issuer.example.com");
    }

    @Test
    void theUsernameClaimDefaultsToUaasUserName() {
        StandardEnvironment environment = environment("app.auth.mode", "oidc");

        processor(VCAP_SERVICES).postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.security.oauth2.client.provider.sso.user-name-attribute")).isEqualTo("user_name");
    }
}
