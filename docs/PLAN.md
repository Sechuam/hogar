# Plan por fases — Hogar

Estado: ⬜ pendiente · 🟡 en curso · ✅ terminada (con `./mvnw verify` en verde)

Cada fase: rama `fase-N-<nombre>`, commits pequeños, merge a `main` al cerrar y resumen
(hecho / tests / decisiones / siguiente paso). Decisiones nuevas → `docs/DECISIONS.md`.

---

## Fase 0 — Setup ✅
**Rama:** `fase-0-setup` · **Ejecuta:** coordinador + `devops-docs`

- [x] `git init`, `.gitignore`, `.editorconfig`.
- [x] Proyecto Spring Boot 3.5.x / Java 21 con Maven Wrapper, paquete base `com.hogar`.
- [x] Dependencias base: web, data-jpa, validation, security, oauth2-resource-server,
      actuator, flyway, postgresql, springdoc, testcontainers, archunit, jacoco.
- [x] `application.yml` + perfiles `dev` / `test`; todo secreto por env (`.env.example`).
- [x] `docker-compose.yml` (Postgres 17 con healthcheck; servicio `app` opcional) y `Dockerfile` multi-stage.
- [x] Migración `V1__baseline.sql` (vacía/extensiones) para fijar Flyway.
- [x] `shared/error`: `GlobalExceptionHandler` con ProblemDetail + test.
- [x] `AbstractIntegrationTest` (Testcontainers + `@ServiceConnection`) y un IT de humo
      (`/actuator/health` = UP, Flyway aplicado).
- [x] `ArchitectureTest` (ArchUnit) con las reglas de `CLAUDE.md`.
- [x] GitHub Actions: `./mvnw -B verify` en push/PR (Temurin 21, caché Maven).
- [x] Subagentes en `.claude/agents/*.md` (los 7 definidos en `CLAUDE.md`).
- [x] `docs/DECISIONS.md` inicial y `README.md` esqueleto.

**Hecho cuando:** `./mvnw verify` verde en local y en CI; `docker compose up` arranca.

---

## Fase 1 — Datos + autenticación ✅
**Rama:** `fase-1-auth`

**Contratos primero (coordinador):** `CurrentUser`, `MembershipGuard`
(`requireMember(householdId)`, `requireOwner(householdId)`), DTOs de auth, `TokenPair`.

**En paralelo:**
- `arquitecto-datos`: migraciones `users`, `user_identities` (proveedor LOCAL/GOOGLE + subject),
  `refresh_tokens`, `households` (`split_policy`, `plan`, `currency`), `memberships`
  (único `user_id`+`household_id`), `invitations`. Entidades e índices.
- `auth-seguridad`:
  - Registro/login email+contraseña (BCrypt), `POST /api/auth/register|login`.
  - `POST /api/auth/google`: valida ID token (firma JWKS de Google, `iss`, `aud` = client IDs
    configurados, `exp`, `email_verified`). Vinculación a cuenta existente si email verificado coincide.
  - Access JWT ~15 min; refresh token opaco (256 bits) guardado como SHA-256, **rotación**
    en cada uso y **detección de reutilización** (si se reusa uno ya rotado → se revoca la familia).
    `POST /api/auth/refresh`, `POST /api/auth/logout`.
  - Rate limiting en login (Bucket4j en memoria, por IP + email) → 429 ProblemDetail.
  - Hogares: crear (creador = OWNER), listar los míos, ver, editar (`splitPolicy`, nombre) solo OWNER.
  - Invitaciones: OWNER genera código (aleatorio, no adivinable) con caducidad; unirse
    con código; OWNER revoca; OWNER expulsa miembro; miembro abandona (último OWNER no puede).
- `qa-tests`: IT de flujos completos y de aislamiento (usuario A no ve hogar de B → 404).

**Hecho cuando:** todos los endpoints con IT, tests de token caducado/manipulado/reusado,
vinculación Google, invitación caducada/revocada.

**Resultado:** 130 tests unitarios + 209 IT en verde; cobertura de líneas ≥ 92 % en todos los
paquetes `service`/`domain` (umbral 80 % activo). Contrato final en `docs/api/fase-1-contratos.md`;
decisiones D-013 a D-033.

---

## Fase 2 — Finanzas: ingresos y gastos ✅
**Rama:** `fase-2-finanzas`

**Contratos:** ver `docs/api/fase-2-contratos.md` (`IncomeQueries`, `HouseholdQueries`,
`SplitCalculator` puro con `SplitResult`, DTOs y endpoints).

- `arquitecto-datos`: `incomes`, `shared_expenses` (con `split_policy_applied`), `expense_shares`
  (`CHECK amount >= 0`), índices por `(household_id, date)`.
- `finanzas`:
  - CRUD de ingresos (un miembro solo gestiona los suyos; los ve todo el hogar). Ingreso
    puntual o recurrente mensual (`startDate`, `endDate` opcional).
  - CRUD de gastos con categoría (enum: RENT, GROCERIES, ELECTRICITY, WATER, GAS, INTERNET,
    CLEANING, OTHER), pagador = miembro del hogar.
  - **Reparto** al crear (y al editar importe/fecha, que recalcula explícitamente):
    - `EQUAL` y `PROPORTIONAL` (ingresos del mes natural de la fecha del gasto).
    - Redondeo: cada share se trunca a 2 decimales (`RoundingMode.DOWN`) y los céntimos
      restantes se reparten uno a uno por **mayor resto** (método de Hamilton); empates por
      orden estable (fecha de alta en el hogar, luego `userId`). Invariante: `Σ shares == total`.
    - Sin ingresos en el mes (suma 0) en PROPORTIONAL → EQUAL + `warnings: ["PROPORTIONAL_FALLBACK_NO_INCOME"]`
      en la respuesta y `split_policy_applied = EQUAL` guardado.
    - **Snapshot**: los shares se persisten; cambiar política o ingresos no toca gastos pasados.
  - Listado paginado y filtrable (rango de fechas, categoría, pagador).
- Tests obligatorios del calculador (unitarios + property-based simple con muchos importes aleatorios):
  importes impares (0,01 €, 10,00/3), 3+ miembros, un miembro con ingreso 0, todos 0,
  un solo miembro, cambio de política posterior que no altera el histórico.

**Resultado:** 243 tests unitarios + 338 IT en verde (propiedad de 10 000 casos del reparto,
invariante Σ shares == importe comprobada también por SQL, snapshot frente a cambios de política,
ingresos y miembros, concurrencia sin deadlocks, sin N+1). Cobertura ≥ 98 % en `income.*`,
`expense.*` y `shared.money`. Decisiones D-034 a D-046; contrato en `docs/api/fase-2-contratos.md`.

---

## Fase 3 — Tareas de casa ✅
**Rama:** `fase-3-tareas` · **Puede ir en paralelo con la Fase 4** (no comparten ficheros).

- `tasks`: título, descripción, asignado, `recurrence` (NONE/DAILY/WEEKLY/MONTHLY),
  `dueDate`, estado (PENDING/DONE), `rotation` opcional (lista ordenada de miembros).
- `task_completions`: histórico (quién, cuándo).
- Completar una tarea recurrente registra la compleción, avanza `dueDate` según periodicidad
  y, si hay rotación, asigna al siguiente miembro activo (salta miembros que ya no están).
- Filtros: mías, pendientes, vencidas. IT de rotación con 3 miembros y miembro que abandona.

**Resultado:** 381 tests unitarios + 443 IT en verde (periodicidad con 20 000 casos aleatorios por
tipo, rotación con abandono y expulsión, compleciones concurrentes 200 + 409, sin N+1 en listados).
QA encontró y se corrigieron fechas a.C. que corrompían datos, títulos de espacios Unicode y páginas
enormes → 500, también en gastos e ingresos. Decisiones D-047 a D-052; contrato en
`docs/api/fase-3-contratos.md`.

---

## Fase 4 — Facturas (adjuntos) ✅
**Rama:** `fase-4-facturas` · paralelo con Fase 3.

- `StorageService` (interfaz: `store`, `load`, `delete`) con `LocalFileSystemStorage` ahora;
  `S3Storage` preparado por configuración en el futuro (sin implementar o detrás de perfil).
- `POST/GET/DELETE /api/households/{id}/expenses/{expenseId}/invoice` (multipart).
- Validación: tamaño máx. configurable (10 MB), tipo por **magic bytes** (PDF, JPEG, PNG, WebP),
  no fiarse de la extensión ni del `Content-Type`. Nombre en disco = UUID (no el original).
- Descarga solo para miembros; `Content-Disposition` seguro. Tests con ficheros falsos (exe renombrado a .pdf).

**Resultado:** 540 tests unitarios + 549 IT en verde (con la Fase 3). Ficheros falsos y polyglots,
nombres hostiles, límites reales de 10 MB, carreras deterministas subida/borrado, fallo real de BD sin
huérfanos. QA corrigió un 500 por nombres largos con emojis, multipart roto → 500 y un falso 413.
Decisiones D-053 a D-057; contrato en `docs/api/fase-4-contratos.md`.

---

## Fase 5 — Balances y liquidaciones ⬜
**Rama:** `fase-5-balances`

- `Balance` **calculado** (no almacenado): `pagado − debido + liquidaciones enviadas − recibidas`.
  Invariante: `Σ balances del hogar == 0`.
- `settlements`: registro de "A pagó X a B" (no mueve dinero). Crear/listar/borrar.
- `GET /balances` y `GET /settlements/suggested`: simplificación de deudas con algoritmo
  voraz (mayor deudor ↔ mayor acreedor) → ≤ n−1 transferencias. Deterministico.
- Tests: hogar de 2 y 4 miembros, todo saldado, céntimos.

---

## Fase 6 — Pulido ⬜
**Rama:** `fase-6-pulido`

- OpenAPI completo (descripciones, ejemplos, esquema de seguridad bearer, ProblemDetail).
- README con arquitectura, decisiones de diseño y diagrama ER.
- Paginación/filtros revisados en todos los listados.
- Observabilidad: Actuator (health, info, metrics, prometheus), logs estructurados JSON,
  correlation id en MDC y cabecera `X-Request-Id`.
- Colección de peticiones (`http/*.http` de IntelliJ/VS Code + Bruno o Postman).
- Revisión final de `qa-tests`: cobertura, casos límite, análisis de seguridad.

---

## Fuera de alcance (por ahora)
Cliente móvil (React Native + Expo), pagos/RevenueCat (solo campo `plan`), notificaciones push,
multi-moneda dentro de un hogar, S3 real, borrado de cuenta (RGPD) — candidato a Fase 7.
