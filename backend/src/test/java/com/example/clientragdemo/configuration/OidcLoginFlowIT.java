package com.example.clientragdemo.configuration;

import com.example.clientragdemo.TestcontainersConfiguration;
import com.example.clientragdemo.users.application.domain.model.User;
import com.example.clientragdemo.users.application.port.out.LoadUserByUsernamePort;
import com.example.clientragdemo.users.application.port.out.SaveUserPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real OpenID Connect authorization-code round trip: the app in {@code app.auth.mode=oidc} against a
 * Keycloak container, configured the way any non-Cloud-Foundry environment would be - just
 * {@code APP_AUTH_OIDC_ISSUER_URI}, {@code _CLIENT_ID} and {@code _CLIENT_SECRET}, with the registration built
 * from the issuer's discovery document. Keycloak is not UAA, but both are OIDC-compliant, which is all this
 * relies on; the realm adds a {@code user_name} claim so the username attribute is the one the Tanzu SSO tile
 * uses. What it cannot show is the tile's own details (bind parameters, issuer path) - see CLAUDE.md.
 *
 * <p>Fixed ports (the app on 18089, Keycloak on 18090) because the realm's redirect URI is static and the
 * issuer has to be known when the properties below are read. Cookies are tracked by hand, as in
 * dmfrey/stig's OAuth2LoginFlowTests: the JDK's cookie manager mishandles single-label hosts like localhost.
 */
@Import(TestcontainersConfiguration.class)
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT, properties = {
        "server.port=" + OidcLoginFlowIT.APP_PORT,
        "app.auth.mode=oidc",
        "app.auth.oidc.issuer-uri=http://localhost:" + OidcLoginFlowIT.KEYCLOAK_PORT + "/realms/rocky",
        "app.auth.oidc.client-id=rocky-app",
        "app.auth.oidc.client-secret=rocky-app-secret"
})
class OidcLoginFlowIT {

    static final int APP_PORT = 18089;
    static final int KEYCLOAK_PORT = 18090;
    private static final String APP = "http://localhost:" + APP_PORT;
    private static final String FRONTEND = "http://localhost:5173";

    private static final Pattern LOGIN_FORM_ACTION = Pattern.compile("id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"");
    private static final Pattern JSON_STRING = Pattern.compile("\"%s\":\"([^\"]*)\"");
    private static final HttpClient CLIENT = HttpClient.newHttpClient(); // does not follow redirects

    @Container
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(DockerImageName.parse("docker.io/keycloak/keycloak:26.2"))
            .withEnv("KEYCLOAK_ADMIN", "admin")
            .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/rocky-realm.json"), "/opt/keycloak/data/import/rocky-realm.json")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/rocky").forStatusCode(200));

    static {
        // The issuer is fixed in the properties above, so the container's port has to be too.
        KEYCLOAK.setPortBindings(List.of(KEYCLOAK_PORT + ":8080"));
    }

    @Autowired
    private LoadUserByUsernamePort loadUser;

    @Autowired
    private SaveUserPort saveUser;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void ssoLoginCreatesTheUserAndEstablishesASession() throws Exception {
        CookieJar cookies = new CookieJar();

        logIn(cookies, "alice");

        HttpResponse<String> me = get(cookies, APP + "/api/auth/me");
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("\"username\":\"alice\"").contains("\"firstName\":\"Alice\"")
                .contains("\"lastName\":\"Anderson\"").contains("\"email\":\"alice@example.com\"");

        User provisioned = loadUser.loadByUsername("alice").orElseThrow();
        assertThat(provisioned.isExternallyAuthenticated()).as("an SSO user has no local password").isTrue();
        // password_hash is NOT NULL and cannot be altered where the app's role does not own the table, so the
        // row holds a marker that is not a valid BCrypt hash.
        assertThat(jdbc.queryForObject("select password_hash from users where username = 'alice'", String.class)).isEqualTo("{sso}");
    }

    @Test
    void aLaterLoginReusesTheSameUser() throws Exception {
        logIn(new CookieJar(), "alice");
        Long first = loadUser.loadByUsername("alice").orElseThrow().id();

        CookieJar again = new CookieJar();
        logIn(again, "alice");

        assertThat(loadUser.loadByUsername("alice").orElseThrow().id()).isEqualTo(first);
        assertThat(get(again, APP + "/api/auth/me").statusCode()).isEqualTo(200);
    }

    @Test
    void anSsoLoginForAUsernameThatBelongsToALocalAccountIsRefused() throws Exception {
        saveUser.save(new User(null, "bob", "$2a$10$notARealHashJustSomethingNonNull", null, null, null, Instant.now()));
        CookieJar cookies = new CookieJar();

        HttpResponse<String> landing = logIn(cookies, "bob");

        assertThat(location(landing).toString()).as("sent back to the frontend with the failure flag").isEqualTo(FRONTEND + "/?login_error");
        assertThat(get(cookies, APP + "/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(loadUser.loadByUsername("bob").orElseThrow().passwordHash()).as("the local account is untouched").startsWith("$2a$");
    }

    @Test
    void logoutEndsTheSessionAndHandsTheFrontendTheIdentityProvidersEndSessionUrl() throws Exception {
        CookieJar cookies = new CookieJar();
        logIn(cookies, "alice");

        HttpResponse<String> logout = send(cookies, HttpRequest.newBuilder(URI.create(APP + "/api/auth/logout"))
                .header(csrfHeaderName(cookies), csrfToken(cookies)).POST(HttpRequest.BodyPublishers.noBody()));

        assertThat(logout.statusCode()).isEqualTo(200);
        String logoutUrl = json(logout.body(), "logoutUrl").replace("\\u0026", "&").replace("\\/", "/");
        assertThat(logoutUrl).startsWith("http://localhost:" + KEYCLOAK_PORT + "/realms/rocky/protocol/openid-connect/logout")
                .contains("id_token_hint=").contains("post_logout_redirect_uri=");
        assertThat(get(cookies, APP + "/api/auth/me").statusCode()).as("the app's own session is gone").isEqualTo(401);

        // The SPA navigates there; Keycloak ends its session and sends the browser back to the frontend.
        HttpResponse<String> endSession = get(cookies, logoutUrl);
        assertThat(endSession.statusCode()).isEqualTo(302);
        assertThat(location(endSession).toString()).startsWith(FRONTEND);
    }

    @Test
    void theConsentBannerStillComesFirst() throws Exception {
        HttpResponse<String> response = get(new CookieJar(), APP + "/oauth2/authorization/sso");

        assertThat(response.statusCode()).isEqualTo(428);
    }

    // --- the browser's side of the flow ----------------------------------------------------------------

    /** Acknowledge the banner, sign in at Keycloak, and follow the redirects back. Returns the last response. */
    private static HttpResponse<String> logIn(CookieJar cookies, String username) throws IOException, InterruptedException {
        // The banner acknowledgment is a POST, so it needs the session's CSRF token first.
        HttpResponse<String> ack = send(cookies, HttpRequest.newBuilder(URI.create(APP + "/api/banner/ack"))
                .header(csrfHeaderName(cookies), csrfToken(cookies)).POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(ack.statusCode()).isEqualTo(204);

        // Backend -> Keycloak, following redirects (the sign-in page is the first non-redirect).
        HttpResponse<String> loginPage = get(cookies, APP + "/oauth2/authorization/sso");
        while (isRedirect(loginPage)) {
            loginPage = get(cookies, location(loginPage).toString());
        }
        Matcher form = LOGIN_FORM_ACTION.matcher(loginPage.body());
        assertThat(form.find()).as("Keycloak login form in: %s", loginPage.body()).isTrue();

        HttpResponse<String> afterLogin = send(cookies, HttpRequest.newBuilder(URI.create(form.group(1).replace("&amp;", "&")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=password")));
        assertThat(afterLogin.statusCode()).as("login POST: %s", afterLogin.body()).isEqualTo(302);

        // Follow only while the redirects stay on the app (Keycloak -> the callback -> the frontend, which is
        // not running here: that final redirect is what we stop at).
        HttpResponse<String> response = afterLogin;
        while (isRedirect(response) && location(response).toString().startsWith(APP)) {
            response = get(cookies, location(response).toString());
        }
        return response;
    }

    private static String csrfHeaderName(CookieJar cookies) throws IOException, InterruptedException {
        return json(get(cookies, APP + "/api/csrf").body(), "headerName");
    }

    private static String csrfToken(CookieJar cookies) throws IOException, InterruptedException {
        return json(get(cookies, APP + "/api/csrf").body(), "token");
    }

    private static String json(String body, String key) {
        Matcher matcher = Pattern.compile(JSON_STRING.pattern().formatted(key)).matcher(body);
        assertThat(matcher.find()).as("\"%s\" in %s", key, body).isTrue();
        return matcher.group(1);
    }

    private static HttpResponse<String> get(CookieJar cookies, String url) throws IOException, InterruptedException {
        return send(cookies, HttpRequest.newBuilder(URI.create(url)).GET());
    }

    private static HttpResponse<String> send(CookieJar cookies, HttpRequest.Builder builder) throws IOException, InterruptedException {
        if (!cookies.header().isEmpty()) {
            builder.header("Cookie", cookies.header());
        }
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        cookies.update(response);
        return response;
    }

    private static boolean isRedirect(HttpResponse<?> response) {
        return response.statusCode() >= 300 && response.statusCode() < 400;
    }

    private static URI location(HttpResponse<?> response) {
        return response.uri().resolve(response.headers().firstValue("Location").orElseThrow());
    }

    private static final class CookieJar {

        private final Map<String, String> values = new LinkedHashMap<>();

        void update(HttpResponse<?> response) {
            for (String setCookie : response.headers().allValues("Set-Cookie")) {
                String pair = setCookie.split(";", 2)[0];
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    values.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
                }
            }
        }

        String header() {
            return values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("; "));
        }
    }
}
