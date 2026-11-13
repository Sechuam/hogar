# Hogar API

> Nombre provisional. Proyecto de portfolio — backend Java.

API REST para parejas y compañeros de piso: ingresos, gastos comunes con **reparto
configurable** (a partes iguales o proporcional a ingresos), facturas adjuntas, tareas de casa
y liquidaciones ("quién debe a quién"). La app no mueve dinero: solo registra y calcula.

**Estado:** Fase 0 (setup) — ver [`docs/PLAN.md`](docs/PLAN.md).

## Stack
Java 21 · Spring Boot 3.5 · PostgreSQL 17 · Flyway · Spring Security (JWT) · springdoc-openapi ·
JUnit 5 · Mockito · Testcontainers · ArchUnit · Docker Compose · GitHub Actions

## Arrancar en local

Requisitos: JDK 21 y Docker.

```bash
cp .env.example .env        # y pon una DB_PASSWORD
docker compose up -d db     # PostgreSQL
./mvnw spring-boot:run      # API en http://localhost:8080
```

O todo en contenedores: `docker compose up --build`.

- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health

## Tests

```bash
./mvnw verify   # unitarios + integración (Testcontainers, necesita Docker) + cobertura JaCoCo
```

- Unitarios (`*Test`): lógica pura y servicios con Mockito, sin contexto de Spring.
- Integración (`*IT`): app completa contra PostgreSQL real en Testcontainers.
- Arquitectura: `ArchitectureTest` hace cumplir las reglas de dependencias entre capas y features.

Informe de cobertura: `target/site/jacoco/index.html`.

## Arquitectura

Monolito modular por feature (`user`, `auth`, `household`, `income`, `expense`, `invoice`,
`task`, `settlement`) con capas `controller → service → repository/domain` y DTOs separados de
las entidades. Detalle y convenciones en [`CLAUDE.md`](CLAUDE.md).

## Decisiones de diseño

Registradas en [`docs/DECISIONS.md`](docs/DECISIONS.md). Destacadas:

- **Errores** uniformes como ProblemDetail (RFC 9457) con `code` estable.
- **Aislamiento** entre hogares: un recurso de otro hogar responde 404.
- **Dinero** siempre con `BigDecimal`; reparto con redondeo determinista (mayor resto) que
  garantiza que la suma de las partes es el total.
- **Snapshot** de los repartos: cambiar la política o los ingresos no reescribe el histórico.
