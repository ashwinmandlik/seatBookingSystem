package io.seatreserve.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds the authenticated user (the JWT subject, never anything from the body)
 * to the MDC, so every log line for the request carries {@code user_id}.
 * Registered inside the security filter chain, right after authentication.
 * {@link RequestCorrelationFilter} clears the MDC when the request ends.
 */
public class AuthenticatedUserMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            MDC.put(RequestCorrelationFilter.USER_ID, auth.getName());
        }
        chain.doFilter(request, response);
    }
}
