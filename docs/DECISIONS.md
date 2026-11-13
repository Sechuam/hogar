# Registro de decisiones

Formato corto tipo ADR. Estado: **Aceptada** · **Provisional** (revisable sin coste) · **Sustituida**.

---

## D-001 · Spring Boot 3.5 y no 4.x — Aceptada (Fase 0)
**Contexto:** el enunciado pide Spring Boot 3. Boot 4 ya existe.
**Decisión:** Spring Boot 3.5.x (última línea 3.x, con soporte), Java 21.
**Alternativas:** Boot 4 (Jakarta EE 11, Jackson 3): más nuevo, pero menos ejemplos y librerías
(springdoc, Bucket4j) aún migrando.
**Consecuencias:** migrar a 4.x será una fase propia más adelante.

## D-002 · Monolito modular por feature con capas internas — Aceptada (Fase 0)
**Decisión:** paquetes `com.hogar.<feature>.{controller,service,repository,domain,dto}` y
`com.hogar.shared`. Entre features solo se usa `service` (interfaces) y `dto`. Las reglas se
**verifican con ArchUnit** (`ArchitectureTest`), no solo se documentan.
**Alternativas:** capas globales (`controllers/`, `services/`…): mezcla dominios; Spring Modulith:
potente pero añade conceptos que no aportan a este tamaño.
**Consecuencias:** las entidades de una feature referencian a otras **por ID** (UUID), no con
asociaciones JPA cruzadas. Más consultas explícitas, pero límites claros y extraíbles.

## D-003 · Paquete base `com.hogar`, `groupId` `com.hogar` — Provisional (Fase 0)
Nombre provisional del proyecto. Renombrar paquetes es mecánico mientras el código sea pequeño.

## D-004 · Formato de errores: ProblemDetail con `code` estable — Aceptada (Fase 0)
**Decisión:** todas las respuestas de error son `application/problem+json` (RFC 9457, sucesor
de 7807) con `type = https://hogar.app/problems/<code>` y propiedad `code`. Validación añade
`errors[{field, message}]`. Los 500 nunca exponen el mensaje interno. El 401 de Spring Security
también sale como ProblemDetail.
**Consecuencias:** el cliente móvil traduce por `code`, no por texto.

## D-005 · Recurso de otro hogar → 404, no 403 — Aceptada (Fase 0)
No revelar la existencia de recursos ajenos. 403 solo cuando el usuario **es** miembro pero le
falta el rol (MEMBER intentando una acción de OWNER).

## D-006 · Secretos y configuración — Aceptada (Fase 0)
Todo secreto por variable de entorno. Para desarrollo, la app importa un `.env` local opcional
(`spring.config.import: optional:file:.env[.properties]`), el mismo que usa Docker Compose:
una sola fuente, sin dependencias extra. `.env` está en `.gitignore`; se versiona `.env.example`.

## D-007 · Testcontainers con contenedor singleton — Aceptada (Fase 0)
`AbstractIntegrationTest` arranca un PostgreSQL por JVM con `@ServiceConnection`. Con
`@Container` por clase, el contenedor se pararía entre clases y el contexto de Spring cacheado
apuntaría a una BD muerta. Los IT se ejecutan con Failsafe (`*IT`) en `verify`.

## D-008 · Moneda única por hogar (EUR por defecto) — Provisional (Fase 0)
Un campo `currency` (ISO-4217) en el hogar; no se mezclan monedas dentro de un hogar.
Multi-moneda queda fuera de alcance.

## D-009 · PROPORTIONAL con ingreso 0 de un miembro — Provisional (Fase 0)
**Decisión:** el reparto es literal: quien tiene 0 € de ingresos en el mes paga 0 €. Solo se
cae a EQUAL (con warning) cuando la **suma** de ingresos del hogar en ese mes es 0.
**Alternativa:** caer a EQUAL si cualquier miembro tiene ingreso 0. Pendiente de validar con
el product owner antes de la Fase 2.

## D-010 · Ingresos recurrentes = mensuales — Provisional (Fase 0)
Un ingreso recurrente cuenta en cada mes natural entre `startDate` y `endDate` (opcional).
Otras periodicidades (semanal, anual) fuera de alcance por ahora.

## D-011 · Redondeo de shares: truncar + mayor resto — Aceptada (Fase 0)
Cada share se trunca a 2 decimales; los céntimos sobrantes se asignan de uno en uno a los
mayores restos (método de Hamilton), desempate por fecha de alta en el hogar y luego `userId`.
Determinista, justo (nadie recibe más de 1 céntimo extra) y garantiza `Σ shares == total`.
**Alternativa descartada:** cargar todo el sobrante al pagador o al primer miembro: sesgo sistemático.

## D-012 · Repositorio solo local por ahora — Provisional (Fase 0)
Git local con rama por fase. Crear el repositorio remoto en GitHub y publicar queda a decisión
del propietario (el workflow de CI ya está listo para cuando se suba).

---

## D-013 · JWT propio HS256 con secreto por entorno — Aceptada (Fase 1)
**Decisión:** access tokens firmados con HMAC-SHA256 (`NimbusJwtEncoder`/`Decoder` de Spring
Security), secreto Base64 ≥ 32 bytes en `JWT_SECRET`; la app no arranca si falta o es corto.
Vida 15 min. El resource server de Spring valida firma, `iss` y `exp`.
**Alternativas:** RS256 con par de claves (necesario si otros servicios verificaran tokens sin
poder firmarlos; aquí emisor y verificador son el mismo proceso); Keycloak/Auth0 (más
infraestructura de la que este proyecto necesita y oculta lo que se quiere demostrar).
**Consecuencias:** rotar el secreto invalida todos los access tokens (aceptable: duran 15 min y
los refresh tokens son opacos e independientes del secreto).

## D-014 · `updated_at` explícito en los métodos de dominio — Aceptada (Fase 1)
Cada método que modifica una entidad recibe `Instant now` (del `Clock` inyectado) y actualiza
`updated_at`. `@UpdateTimestamp`/`@PreUpdate` usan el reloj del sistema y saltan el `Clock`,
lo que impide tests deterministas.

## D-015 · IDs asignados en la aplicación sin `merge` innecesario — Aceptada (Fase 1)
UUID generados al crear la entidad. Para que `save()` haga `persist` (y no `merge` con SELECT
previo), las entidades con `@Version` usan `version == null` como "nueva" y las demás implementan
`Persistable<UUID>` con un flag transitorio que se apaga en `@PostLoad`/`@PostPersist`.

## D-016 · Membresías con baja lógica (`left_at`) — Aceptada (Fase 1)
Abandonar o ser expulsado rellena `left_at` en vez de borrar la fila: gastos, repartos e
historial de tareas seguirán referenciando a ese usuario. Un índice único parcial
`(household_id, user_id) WHERE left_at IS NULL` garantiza una sola membresía activa y permite
volver a unirse.

## D-017 · Agregados referenciados por UUID también dentro de una feature — Aceptada (Fase 1)
Solo `User → UserIdentity` es asociación JPA (mismo agregado). `Membership`, `Invitation` y
`RefreshToken` guardan UUIDs: sin cargas perezosas accidentales ni ciclos, y cada agregado se
modifica en su propia transacción.

## D-018 · Invariantes críticas duplicadas en BD — Aceptada (Fase 1)
`CHECK` de email en minúsculas, `uses <= max_uses`, coherencia de fechas, valores de enums.
La aplicación es la primera barrera; la BD es la red de seguridad ante bugs o carreras.

## D-019 · Último OWNER y aceptación de invitaciones serializados con bloqueo de fila — Aceptada (Fase 1)
Cambios de rol/abandono bloquean la fila del hogar (`PESSIMISTIC_WRITE`) y aceptar una invitación
bloquea la invitación. Son operaciones poco frecuentes: un bloqueo pesimista breve es más simple
y correcto que reintentos optimistas.

## D-020 · Códigos de invitación en claro — Aceptada (Fase 1)
10 caracteres Crockford Base32 (~50 bits, `SecureRandom`), con caducidad, usos máximos y
revocación. Se guardan en claro porque el OWNER necesita volver a verlos para compartirlos; su
vida corta y la posibilidad de revocarlos acotan el riesgo. Aceptar exige estar autenticado.

## D-021 · Vincular Google a una cuenta no verificada elimina su contraseña — Aceptada (Fase 1)
**Contexto:** el registro con contraseña no verifica el email. Un atacante podría registrarse con
el email de la víctima; cuando esta entrase con Google se vincularía a esa cuenta y el atacante
conservaría la contraseña (*account pre-hijacking*).
**Decisión:** al vincular Google a una cuenta existente con email **no verificado**, se elimina su
contraseña y se revocan todas sus sesiones (refresh tokens). Si el email ya estaba verificado, se
vincula sin más.
**Alternativas:** rechazar la vinculación (deja al dueño legítimo sin acceso a su email);
verificación de email por correo antes de nada (correcto, pero requiere envío de emails: Fase 7).
**Consecuencias:** quien se registró con contraseña y luego usa Google con el mismo email pierde
el acceso por contraseña (sigue entrando con Google). Cuando exista verificación de email, este
caso solo afectará a cuentas sin verificar.

## D-022 · Reutilización de refresh token revoca la familia, aunque sea un reintento legítimo — Aceptada (Fase 1)
Rotación con `SELECT … FOR UPDATE`: dos refresh simultáneos del mismo token se serializan y el
segundo cuenta como reutilización. Se prioriza detectar robo de tokens sobre la comodidad: un
cliente que reintenta tras un timeout tendrá que volver a iniciar sesión. Revocación confirmada
pese al 401 (`noRollbackFor`). Token caducado → 401 sin revocar la familia.

## D-023 · Rate limiting en memoria con Bucket4j — Aceptada (Fase 1)
`capacity` intentos por `refill-period` con recarga gradual, por IP + email (Google: solo IP),
`Retry-After` en el 429. Buckets en Caffeine acotada. La IP sale de `getRemoteAddr()`: tras un
proxy habrá que configurar `server.forward-headers-strategy` (nunca leer `X-Forwarded-For` a mano).
**Limitación:** por instancia; con varias réplicas, pasar a Bucket4j sobre Redis/PostgreSQL.

## D-024 · Login sin oráculo de existencia de email — Aceptada (Fase 1)
Email inexistente, usuario solo-Google o contraseña errónea → mismo 401 `invalid-credentials` y
mismo coste (BCrypt contra un hash "dummy"). El registro sí devuelve 409 si el email existe: es
inevitable en un registro sin verificación por correo y el rate limiting acota la enumeración.

## D-025 · La feature auth no toca entidades de user — Aceptada (Fase 1)
`auth` solo emite y rota tokens; las cuentas se gestionan vía la interfaz pública
`user.service.UserAccounts` (devuelve DTOs). Lo impone ArchUnit.

## D-026 · Verificación de Google ID token con `NimbusJwtDecoder` — Aceptada (Fase 1)
Decoder propio (`@Qualifier("googleIdTokenDecoder")`, nunca `@Primary`) contra el JWKS de Google:
firma RS256, `exp`/`iat` con el `Clock`, `iss` ∈ {`accounts.google.com`, `https://accounts.google.com`},
`aud` ∩ `GOOGLE_CLIENT_IDS`. Sin la librería `google-api-client`: Spring Security ya trae lo
necesario. La verificación va fuera de transacción para no retener conexiones mientras se
descarga el JWKS.

## D-027 · Normalización de códigos de invitación — Aceptada (Fase 1)
Al aceptar: trim, mayúsculas, sin guiones y equivalencias Crockford (O→0, I/L→1), para tolerar
códigos dictados o copiados a mano. Invitación a un hogar sin miembros activos → 404 (nadie entra
como MEMBER en un hogar sin OWNER).
