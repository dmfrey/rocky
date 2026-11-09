package com.example.clientragdemo.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Keeps single sign-on behind the consent banner. {@link BannerAckFilter} runs after Spring Security's
 * chain, but the OAuth2 login filters answer their own requests (a redirect to the identity provider, then
 * the callback) inside that chain, so the banner filter never sees them - without this, SSO would be
 * reachable before the banner is acknowledged, which is exactly the property the banner gate exists to
 * prevent. Registered ahead of those filters, and only for their two paths.
 */
class OAuth2LoginBannerGate extends OncePerRequestFilter {

    private static final String AUTHORIZATION_PREFIX = "/oauth2/authorization/";
    private static final String CALLBACK_PREFIX = "/login/oauth2/code/";
    private static final int SC_PRECONDITION_REQUIRED = 428;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith(AUTHORIZATION_PREFIX) || path.startsWith(CALLBACK_PREFIX));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute(BannerAckFilter.SESSION_ATTRIBUTE))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(SC_PRECONDITION_REQUIRED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Consent banner must be acknowledged\"}");
    }
}
