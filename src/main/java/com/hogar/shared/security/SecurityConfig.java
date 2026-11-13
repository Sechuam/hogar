package com.hogar.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hogar.shared.error.Problems;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * API stateless autenticada con el access token propio (Bearer JWT, ver {@link JwtConfig}).
 * Sin sesión ni CSRF: no hay cookies, el token viaja en la cabecera Authorization.
 *
 * <p>La autorización fina (membresía y rol en cada hogar) no se expresa aquí sino en los
 * servicios mediante {@link MembershipGuard}, porque depende del recurso concreto.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    static final String[] PUBLIC_PATHS = {
            "/actuator/health/**", "/actuator/info",
            "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
            "/error"
    };

    /** Endpoints de autenticación: públicos, protegidos por rate limiting en la feature auth. */
    static final String[] AUTH_PATHS = {
            "/api/auth/register", "/api/auth/login", "/api/auth/google",
            "/api/auth/refresh", "/api/auth/logout"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        var entryPoint = new ProblemDetailAuthenticationEntryPoint(objectMapper);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, AUTH_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> { })
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler(objectMapper)))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, ex) -> {
            ProblemDetail problem = Problems.of(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "No tienes permisos para realizar esta acción.");
            problem.setInstance(URI.create(request.getRequestURI()));
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), problem);
        };
    }
}
