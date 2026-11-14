package com.hogar.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Seguridad del puerto de gestión ({@code management.server.port}): Actuator (health, info,
 * metrics, prometheus) sin autenticación, porque ese puerto no se publica fuera de la red
 * interna (Docker Compose no lo expone) y Prometheus lo raspa sin credenciales.
 *
 * <p>Spring Boot aplica la misma cadena de filtros de seguridad al contexto de gestión, así
 * que esta cadena se selecciona por el <em>puerto local</em> de la petición, no por la ruta:
 * {@code local.management.port} solo existe cuando el puerto de gestión es distinto del de la
 * aplicación. Si alguien configurase el mismo puerto, esta cadena no se aplica y los endpoints
 * de Actuator caen en {@link SecurityConfig} (métricas protegidas por JWT).
 */
@Configuration(proxyBeanMethods = false)
public class ManagementSecurityConfig {

    static final String LOCAL_MANAGEMENT_PORT = "local.management.port";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain managementSecurityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        return http
                .securityMatcher(request -> isManagementPort(request, environment))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    static boolean isManagementPort(HttpServletRequest request, Environment environment) {
        Integer managementPort = environment.getProperty(LOCAL_MANAGEMENT_PORT, Integer.class);
        return managementPort != null && managementPort == request.getLocalPort();
    }
}
