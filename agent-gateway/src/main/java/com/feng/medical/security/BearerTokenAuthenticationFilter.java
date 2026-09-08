package com.feng.medical.security;

import com.feng.medical.auth.AuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final AuthenticationService authenticationService;

    public BearerTokenAuthenticationFilter(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Agent Core callbacks have their own audience-bound service JWT verifier.
        return request.getRequestURI().startsWith("/api/internal/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!authorization.startsWith("Bearer ") || authorization.length() <= "Bearer ".length()) {
            unauthorized(response, "invalid bearer token");
            return;
        }
        try {
            AuthenticatedUser user = authenticationService.authenticateAccessToken(authorization.substring("Bearer ".length()));
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    user,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (InvalidAccessTokenException exception) {
            SecurityContextHolder.clearContext();
            unauthorized(response, exception.getMessage());
        }
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"" + jsonEscape(message) + "\"}");
    }

    private String jsonEscape(String message) {
        return message.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
