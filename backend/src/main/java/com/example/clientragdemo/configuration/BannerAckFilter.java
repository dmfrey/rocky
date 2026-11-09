package com.example.clientragdemo.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Backend-enforced consent-banner gate (the DoD Notice and Consent Banner requirement) - not a
// frontend-only splash screen. Registered on /* (see SecurityConfig), so every request other than
// the exempt ones below is rejected with 428 until this session has explicitly acknowledged the
// banner via POST /api/banner/ack. This runs after Spring Security's own filter chain (default
// FilterRegistrationBean order), so it only ever sees requests Spring Security already decided to
// let through - an unauthenticated request to a protected route still gets Security's own 401
// without ever reaching here, which is fine (still "no access granted"). What matters is that
// SecurityConfig's permitAll() routes (/api/auth/login, /api/auth/register) DO reach this filter,
// so they're correctly 428'd pre-acknowledgment too - login/registration must not be reachable
// before the banner is acked, exactly the property this feature exists to provide.
class BannerAckFilter extends OncePerRequestFilter {

    static final String SESSION_ATTRIBUTE = "app.banner.acknowledged";

    private static final String BANNER_PATH_PREFIX = "/api/banner";

    // Anonymous, session-less automated tooling (platform health probes, Prometheus scraping) -
    // see application.yaml's management.endpoints.web.exposure.include, the only two actuator
    // endpoints exposed at all. Listed explicitly rather than exempting the whole /actuator tree,
    // since a blanket exemption would also cover any actuator endpoint added later without
    // anyone revisiting this filter.
    private static final String ACTUATOR_HEALTH_PATH_PREFIX = "/actuator/health";

    private static final String ACTUATOR_PROMETHEUS_PATH_PREFIX = "/actuator/prometheus";

    private static final String ERROR_PATH_PREFIX = "/error";

    // The CSRF token has to be fetchable before the banner can be acknowledged: the acknowledgment is a
    // POST, and every POST needs the token (see CsrfController).
    private static final String CSRF_PATH = "/api/csrf";

    private static final int SC_PRECONDITION_REQUIRED = 428;

    private final BannerProperties properties;

    BannerAckFilter(BannerProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isExempt(request) || isAcknowledged(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        // Deliberately not response.sendError(...): that triggers a servlet-container forward to
        // /error, which re-enters the *entire* filter chain (including Spring Security) for the
        // forwarded dispatch. /error isn't permitAll'd, so on an unauthenticated request Spring
        // Security's own authenticationEntryPoint (see SecurityConfig) fires for that forwarded
        // request and overwrites this 428 with its own 401 before it ever reaches the client -
        // confirmed against a real request, not assumed. Writing the response directly bypasses
        // that forward entirely.
        response.setStatus(SC_PRECONDITION_REQUIRED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Consent banner must be acknowledged\"}");
    }

    private boolean isExempt(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith(BANNER_PATH_PREFIX) || path.startsWith(ACTUATOR_HEALTH_PATH_PREFIX)
                || path.startsWith(ACTUATOR_PROMETHEUS_PATH_PREFIX) || path.startsWith(ERROR_PATH_PREFIX)
                || path.equals(CSRF_PATH)) {
            return true;
        }
        return this.properties.exemptPathPrefixes().stream().anyMatch(path::startsWith);
    }

    private boolean isAcknowledged(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_ATTRIBUTE));
    }
}
