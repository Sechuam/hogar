package com.hogar.support;

import com.hogar.shared.security.JwtProperties;
import com.hogar.user.repository.UserRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base de los tests de integración: arranca la aplicación completa contra un PostgreSQL real
 * en Testcontainers.
 *
 * <p>El contenedor es un <em>singleton</em> (se arranca una vez por JVM y lo limpia Ryuk al
 * terminar) en vez de usar {@code @Testcontainers}/{@code @Container}, que lo pararía tras cada
 * clase e invalidaría el contexto de Spring cacheado entre clases.
 *
 * <p>Los datos no se borran entre tests: cada test crea sus propios usuarios/hogares con
 * identificadores únicos ({@link TestUsers}), así que los tests no interfieren entre sí.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private Clock clock;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private TestUsers testUsers;

    /** Helper para crear usuarios autenticados sin pasar por los endpoints de login. */
    protected TestUsers users() {
        if (testUsers == null) {
            testUsers = new TestUsers(userRepository, jwtEncoder, jwtProperties, clock,
                    passwordEncoder.encode(TestUsers.PASSWORD));
        }
        return testUsers;
    }
}
