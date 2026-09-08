package com.feng.medical.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feng.medical.security.AccessTokenProperties;
import com.feng.medical.security.AccessTokenService;
import com.feng.medical.security.BearerTokenAuthenticationFilter;
import java.time.Clock;
import java.time.Duration;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfiguration {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectProvider<BearerTokenAuthenticationFilter> bearerTokenFilter) throws Exception {
        var configured = http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "请登录后重试"))
                        .accessDeniedHandler((request, response, exception) -> writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "当前账号没有访问此资源的权限")))
                .authorizeHttpRequests(authorize -> authorize
                        // SseEmitter completes through an ASYNC dispatch after the authenticated
                        // request has returned. Its security context is intentionally not retained
                        // by this stateless API, so only the continuation dispatch is permitted.
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health/liveness", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/internal/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasAnyRole("USER", "ADMIN")
                        .anyRequest().denyAll());
        BearerTokenAuthenticationFilter filter = bearerTokenFilter.getIfAvailable();
        if (filter != null) {
            configured.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        }
        return configured.build();
    }

    private static void writeJsonError(HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    AccessTokenService accessTokenService(AccessTokenProperties properties, Clock systemClock) {
        return new AccessTokenService(properties.secret(), Duration.ofSeconds(properties.ttlSeconds()), systemClock);
    }

}
