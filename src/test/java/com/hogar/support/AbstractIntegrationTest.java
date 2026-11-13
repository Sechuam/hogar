package com.hogar.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base de los tests de integración: arranca la aplicación completa contra un PostgreSQL real
 * en Testcontainers.
 *
 * <p>El contenedor es un <em>singleton</em> (se arranca una vez por JVM y lo limpia Ryuk al
 * terminar) en vez de usar {@code @Testcontainers}/{@code @Container}, que lo pararía tras cada
 * clase e invalidaría el contexto de Spring cacheado entre clases.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }
}
