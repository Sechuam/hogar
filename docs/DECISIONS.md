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

## D-009 · PROPORTIONAL con ingreso 0 de un miembro — Aceptada (confirmada antes de la Fase 2)
**Decisión:** el reparto es literal: quien tiene 0 € de ingresos en el mes paga 0 €. Solo se
cae a EQUAL (con warning) cuando la **suma** de ingresos del hogar en ese mes es 0.
**Alternativa descartada:** caer a EQUAL si cualquier miembro tiene ingreso 0 (desvirtúa la
política: quien elige PROPORTIONAL acepta que quien no ingresa no aporte ese mes).

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
y además un cupo mayor solo por IP (`ip-capacity`) en registro y login, para que rotar emails desde
una misma IP no esquive el límite (enumeración, *password spraying*). `Retry-After` en el 429. Buckets en Caffeine acotada. La IP sale de `getRemoteAddr()`: tras un
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

## D-028 · Un expulsado no puede reutilizar una invitación anterior a su expulsión — Aceptada (Fase 1)
**Contexto:** QA detectó que un miembro expulsado volvía a entrar con la misma invitación
ilimitada que ya había usado (vive 7 días por defecto), lo que vaciaba de efecto la expulsión.
**Decisión:** `memberships.removed_by` distingue expulsión de abandono (V6). Al aceptar, si el
usuario fue expulsado **después** de crearse esa invitación → 403 `removed-from-household`. Una
invitación creada tras la expulsión sí vale (el OWNER le readmite a sabiendas) y quien se fue por
su cuenta puede volver con la misma.
**Alternativa descartada:** revocar todas las invitaciones vivas al expulsar (castiga a terceros
invitados legítimamente).

## D-029 · Errores de tipo en el JSON como `validation-error` por campo — Aceptada (Fase 1)
Un campo con tipo incorrecto (enum desconocido, texto donde va un número...) devuelve
`validation-error` con `errors[{field, message}]`, igual que Bean Validation; el cliente pinta el
error junto al campo. JSON sintácticamente roto o cuerpo ausente sigue siendo `bad-request`.
Los enums solo se aceptan por nombre (`fail-on-numbers-for-enums`): `{"role":0}` no es OWNER.

## D-030 · Validación estricta de access tokens — Aceptada (Fase 1)
Además de firma, `iss` y ventana temporal, el decoder exige `exp` presente (Spring ignora un `exp`
ausente) y `sub` con formato UUID: un token anómalo da 401, nunca 500.

## D-031 · Violaciones de integridad no anticipadas → 409 `data-conflict` — Aceptada (Fase 1)
Red de seguridad para carreras que el servicio no serializa (p. ej. dos altas simultáneas con
Google del mismo email). Se registra la restricción en el log pero no se expone al cliente.
Un fallo al consultar las claves de Google da 503 `google-unavailable`, no 401 ni 500.

## D-032 · Comprobar membresía antes de bloquear la fila del hogar — Aceptada (Fase 1)
Las operaciones con `SELECT … FOR UPDATE` sobre el hogar verifican antes la membresía sin
bloqueo (un extraño no puede bloquear hogares ajenos) y la repiten ya con el bloqueo.

## D-033 · Cobertura mínima del 80 % de líneas en `service` y `domain` — Aceptada (Fase 1)
`jacoco:check` sobre el informe combinado (unitarios + IT) falla la build por debajo del 80 % en
cualquier paquete `com.hogar.*.service` o `com.hogar.*.domain`. Controllers y configuración se
cubren con IT pero no cuentan para el umbral: el número que importa es el de la lógica de negocio.

**Deuda conocida:** `GET /api/households` hace N+1 (`countActive` por hogar); aceptable con los
pocos hogares por usuario esperados, a resolver con una consulta agregada en la Fase 6.

---

## D-034 · Participan en un gasto los miembros activos al crearlo o recalcularlo — Aceptada (Fase 2)
Un gasto se reparte entre los miembros activos en el momento de registrarlo, aunque su fecha sea
anterior. Reconstruir quién era miembro en una fecha pasada complica el modelo y sorprende al
usuario ("lo apunto hoy y lo pago con quien vive hoy"). Elegir participantes concretos por gasto
queda como mejora futura.

## D-035 · Los enums de dominio son lenguaje publicado entre features — Aceptada (Fase 2)
`SplitPolicy` es el mismo concepto en `household` y en `expense`. Duplicarlo obligaría a mapear
por nombre sin aportar aislamiento real. ArchUnit permite depender de **enums** del `domain` de
otra feature; entidades, repositorios y servicios internos siguen prohibidos.

## D-036 · Paginación con `PageResponse` propio — Aceptada (Fase 2)
`PageResponse(content, page, size, totalElements, totalPages)` en lugar de serializar `Page` de
Spring Data, cuyo JSON no es un contrato estable. `size` máximo 100.

## D-037 · El reparto se calcula en céntimos con aritmética exacta — Aceptada (Fase 2)
`SplitCalculator` trabaja en céntimos (`BigInteger`/`BigDecimal` sin divisiones inexactas) y
asigna el sobrante por mayor resto con desempate por antigüedad en el hogar y `userId`. La suma
de las partes es el total por construcción, y además se comprueba en código. Se guarda el `weight`
de cada parte (ingreso usado o 1) para poder explicar el reparto después.

## D-038 · Unicidad de shares diferida al commit — Aceptada (Fase 2)
Hibernate inserta los hijos nuevos antes de borrar los huérfanos (HHH-2801): recalcular un reparto
con los mismos miembros violaba `UNIQUE(expense_id, user_id)` a mitad de flush. La restricción es
`DEFERRABLE INITIALLY DEFERRED`: se comprueba sobre el estado final de la transacción.

## D-039 · Filtros opcionales con `Specification` — Aceptada (Fase 2)
`(:p is null or …)` en JPQL con UUID/fechas nulas provoca errores de tipo en PostgreSQL. Los
listados filtrables usan `Specification` encapsuladas en métodos `default` del repositorio
(`search(...)`), con orden total fijo (fecha, `createdAt`, `id`) para que la paginación no
duplique ni pierda filas.

## D-040 · Listados de gastos sin fetch join + `@BatchSize` — Aceptada (Fase 2)
Paginar con fetch de colección obliga a Hibernate a paginar en memoria (HHH90003004). Se pagina en
SQL y las shares de toda la página llegan en una sola consulta `IN` (`@BatchSize(100)`, igual al
tamaño máximo de página). El detalle usa `@EntityGraph`.

## D-041 · `LargestRemainder` compartido en `shared.money` — Aceptada (Fase 2)
Mismo algoritmo (mayor resto exacto en enteros) para repartir gastos y para los porcentajes del
resumen de ingresos; vive en `shared` porque lo usan dos features. El desempate por `userId`
compara el UUID como texto (mismo orden que `uuid` en PostgreSQL; `UUID.compareTo` compara con signo).

## D-042 · `paidBy` nulo al editar conserva el pagador actual — Aceptada (Fase 2)
Al crear, `paidBy` nulo = el usuario autenticado. Al editar (PUT), `paidBy` nulo **conserva** el
pagador: si significara "quien edita", un OWNER que corrige la descripción se convertiría en
pagador y el reparto se recalcularía por sorpresa. El pagador solo se valida si cambia, para poder
seguir editando gastos de alguien que ya dejó el hogar.

## D-043 · Paginación fuera de rango → 400, no se acota — Aceptada (Fase 2)
`size` > 100, `size` < 1 o `page` < 0 devuelven 400: acotar en silencio haría creer al cliente
que ha recibido todo. `from > to` → 400 `invalid-date-range` (`BadRequestException` compartida).
