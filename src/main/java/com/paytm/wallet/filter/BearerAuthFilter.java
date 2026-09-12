package com.paytm.wallet.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.wallet.config.AuthTokenProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Simple bearer-token-per-user auth (see AuthTokenProperties for why this is intentionally not
 * Spring Security / OAuth / JWT). /actuator/health and /actuator/prometheus are left open since the
 * brief asks for logs/metrics to be "publicly viewable."
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class BearerAuthFilter extends OncePerRequestFilter {

    private static final String MDC_USER_KEY = "user_id";

    private final Map<String, String> tokenToUserId;
    private final ObjectMapper objectMapper;

    public BearerAuthFilter(AuthTokenProperties authTokenProperties, ObjectMapper objectMapper) {
        this.tokenToUserId = authTokenProperties.asTokenToUserIdMap();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7).trim() : null;
        String userId = token == null ? null : tokenToUserId.get(token);

        if (userId == null) {
            respondUnauthorized(response);
            return;
        }

        CurrentUserContext.set(userId);
        MDC.put(MDC_USER_KEY, userId);
        try {
            chain.doFilter(request, response);
        } finally {
            CurrentUserContext.clear();
            MDC.remove(MDC_USER_KEY);
        }
    }

    private void respondUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
                "error", "unauthorized",
                "message", "Missing or invalid bearer token"
        ));
    }
}
