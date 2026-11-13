# CLAUDE.md — Hogar (nombre provisional)

API backend para parejas y compañeros de piso: ingresos, gastos comunes con reparto
configurable, facturas adjuntas, tareas de casa y liquidaciones ("quién debe a quién").
La app **no mueve dinero real**: solo registra y calcula.

Proyecto de portfolio para entrevistas de backend Java → se valora código limpio,
tests reales y decisiones justificadas (ver `docs/DECISIONS.md`).

## Stack

| Pieza | Elección |
|---|---|
| Lenguaje | Java 21 (records, sealed, pattern matching donde aporte) |
| Framework | Spring Boot 3.5.x (Web, Data JPA, Security, OAuth2 Resource Server, Validation, Actuator) |
| Build | Maven con wrapper (`./mvnw`) |
| BD | PostgreSQL 17, migraciones **Flyway** (`spring.jpa.hibernate.ddl-auto=validate`, nunca `update`) |
| Auth | JWT propio (Nimbus, HS256) + refresh token opaco rotatorio; Google ID token validado con JWKS de Google |
| Docs API | springdoc-openapi (Swagger UI en `/swagger-ui.html`) |
| Tests | JUnit 5, AssertJ, Mockito, Testcontainers (PostgreSQL), ArchUnit, JaCoCo |
| Infra local | Docker Compose (Postgres + app) |
| CI | GitHub Actions: `./mvnw -B verify` |

## Arquitectura

Monolito modular, **paquetes por feature** y capas dentro de cada feature:

```
com.hogar
├── HogarApplication.java
├── shared/                 # transversal, sin lógica de negocio de features
│   ├── error/              # GlobalExceptionHandler (ProblemDetail), excepciones base
│   ├── money/              # Money / MoneyUtils (BigDecimal escala 2, HALF_EVEN)
│   ├── security/           # CurrentUser, MembershipGuard (contrato)
│   ├── web/                # paginación, CorrelationId filter
│   └── config/             # Clock, OpenAPI, Jackson
├── user/        controller/ service/ repository/ domain/ dto/
├── auth/        (registro, login, Google, JWT, refresh, rate limit)
├── household/   (hogares, membresías, roles, invitaciones, splitPolicy, plan)
├── income/
├── expense/     (gastos, shares, políticas de reparto)
├── invoice/     (adjuntos + storage/)
├── task/
└── settlement/  (liquidaciones y balances)
```

### Reglas de dependencia (verificadas con ArchUnit)
- `controller` → `service` → `repository` / `domain`. Nunca `controller` → `repository`.
- Los controllers **solo** reciben y devuelven DTOs (records en `dto/`). Las entidades JPA
  nunca salen de la capa de servicio. Mapeo manual en mappers estáticos (sin MapStruct
  salvo que se justifique).
- Entre features solo se habla a través de **interfaces públicas** del paquete `service`
  (p. ej. `IncomeQueryService`, `MembershipGuard`). Prohibido usar el repositorio o las
  entidades de otra feature.
- `shared` no depende de ninguna feature.

### Convenciones
- **Dinero**: siempre `BigDecimal`, escala 2, `RoundingMode.HALF_EVEN` salvo que la regla
  diga otra cosa; columnas `NUMERIC(19,2)`. **Nunca `double`/`float`.** Una moneda por
  hogar (`currency`, ISO-4217, por defecto `EUR`).
- **IDs**: `UUID` generados en la aplicación. Nunca exponer IDs secuenciales.
- **Tiempo**: `Instant` en UTC para timestamps, `LocalDate` para fechas de negocio
  (fecha de gasto/ingreso). Inyectar `java.time.Clock` (testeable), nunca `now()` directo.
- **Errores**: excepciones de dominio (`NotFoundException`, `ForbiddenException`,
  `ConflictException`, `BusinessRuleException`) → `GlobalExceptionHandler` → `ProblemDetail`
  (RFC 7807) con `type` estable (`https://hogar.app/problems/<codigo>`), `title`, `detail`
  y propiedad `code`. Errores de validación incluyen `errors[]` por campo.
- **Validación**: Bean Validation en DTOs de entrada (`@Valid`); reglas de negocio en servicio.
- **Autorización**: todo endpoint bajo `/api/households/{householdId}/**` comprueba
  membresía vía `MembershipGuard` (y rol OWNER cuando aplique). Un recurso de otro hogar
  devuelve **404**, no 403 (no filtrar existencia).
- **Transacciones**: `@Transactional` en servicios, `readOnly = true` en lecturas. Nunca en controllers.
- **JPA**: `open-in-view=false`, relaciones `LAZY`, `@Version` en agregados editables
  (hogar, gasto, tarea) para bloqueo optimista.
- **REST**: recursos en plural, `/api/...` (versión por cabecera no; prefijo `/api` sin versión
  hasta que haga falta), `201 + Location` al crear, paginación `page/size/sort` con `size` máx. 100.
- **Nombres**: código e identificadores en inglés; documentación y commits en español
  (el tipo de Conventional Commit en inglés: `feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `chore:`, `ci:`).
- **Secretos**: solo por variables de entorno. `.env` está en `.gitignore`; se mantiene `.env.example`.

## Tests
- **Unitarios** (`*Test`): servicios y lógica pura (p. ej. calculadoras de reparto) con
  JUnit 5 + Mockito + AssertJ. Sin Spring context.
- **Integración** (`*IT`, ejecutados por Failsafe en `verify`): `@SpringBootTest` +
  Testcontainers PostgreSQL con `@ServiceConnection`, contenedor compartido (clase base
  `AbstractIntegrationTest`). Prueban HTTP → BD de verdad, incluida seguridad y membresía.
- **Helpers de IT**: `users().create("Ana")` (en `AbstractIntegrationTest`) crea un usuario en BD
  con su access token (`TestUser.bearer()`); los datos no se limpian entre tests, así que cada
  test crea los suyos. `HouseholdApiSupport` tiene atajos para hogares, invitaciones y ProblemDetail.
- **Arquitectura**: `ArchitectureTest` con ArchUnit para las reglas de dependencia.
- JaCoCo genera informe combinado y **falla la build** por debajo del 80 % de líneas en cualquier
  paquete `service`/`domain` (`jacoco:check`).
- Una fase **no está terminada** si `./mvnw verify` no está en verde.

## Comandos

```bash
cp .env.example .env                 # primera vez
docker compose up -d db              # solo Postgres
./mvnw spring-boot:run               # app en local (perfil dev)
./mvnw test                          # tests unitarios
./mvnw verify                        # unitarios + integración (necesita Docker) + JaCoCo
docker compose up --build            # app + BD en contenedores
```

Swagger UI: http://localhost:8080/swagger-ui.html · Health: http://localhost:8080/actuator/health

## Git
- Rama `main` siempre verde. Una rama por fase: `fase-N-<nombre>` (p. ej. `fase-1-auth`).
- Commits pequeños con Conventional Commits. Merge a `main` al cerrar la fase.
- Al cerrar cada fase: resumen (hecho, tests, decisiones, siguiente paso).

## Subagentes (`.claude/agents/`) y propiedad de ficheros
El coordinador define primero contratos (interfaces + DTOs) y luego reparte. Cada agente
solo escribe en su zona; si necesita cambiar un contrato ajeno, lo pide al coordinador.

| Agente | Zona propia |
|---|---|
| `arquitecto-datos` | `src/main/resources/db/migration/**`, entidades `*/domain/**` |
| `auth-seguridad` | `auth/**`, `user/**` (salvo entidades), `household/**` invitaciones/membresía, `shared/security/**` |
| `finanzas` | `income/**`, `expense/**`, `settlement/**`, `shared/money/**` |
| `tareas-hogar` | `task/**` |
| `ficheros-facturas` | `invoice/**` |
| `qa-tests` | `src/test/**` transversal (base de IT, ArchUnit, casos límite); puede proponer fixes |
| `devops-docs` | `Dockerfile`, `docker-compose.yml`, `.github/**`, `README.md`, `docs/api/**`, `http/**` |

Migraciones: un único dueño (`arquitecto-datos`) para evitar choques de numeración Flyway.
Nomenclatura `V<n>__<descripcion>.sql`; una migración aplicada **nunca** se edita.

## Documentos
- `docs/PLAN.md` — plan por fases y estado.
- `docs/DECISIONS.md` — registro de decisiones (formato ADR corto: contexto, decisión, alternativas, consecuencias).
- `README.md` — cómo arrancar y decisiones de diseño destacadas.
