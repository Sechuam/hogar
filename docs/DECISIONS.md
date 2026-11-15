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

## D-044 · Editar o borrar un gasto bloquea su fila — Aceptada (Fase 2)
**Contexto:** QA reprodujo deadlocks (40P01) con dos PUT, o PUT + DELETE, simultáneos sobre el
mismo gasto: durante el flush, las partes (borrado de huérfanas e inserción) y la fila del gasto
se bloqueaban en distinto orden en cada transacción → 500.
**Decisión:** `update` y `delete` cargan la raíz con `SELECT … FOR UPDATE` (sin fetch de partes:
PostgreSQL no permite `FOR UPDATE` sobre el lado nullable de un outer join). Las escrituras sobre
un mismo gasto se serializan; la segunda ve el estado ya confirmado. Como red de seguridad,
`PessimisticLockingFailureException` (bloqueo/deadlock) → 409 `concurrent-modification`, igual
que el bloqueo optimista.

## D-045 · Entrada JSON estricta — Aceptada (Fase 2)
Ningún texto puede contener U+0000 (PostgreSQL no lo almacena: acababa en error de BD) y
`LocalDate` solo se acepta como texto ISO-8601 (un número se interpretaba como *epoch day*). Ambos
casos son `validation-error` del campo. Se configura una vez en `JacksonConfig` para toda la API.

## D-046 · Los errores de BD se registran sin datos — Aceptada (Fase 2)
Ante una violación de integridad se registran solo `SQLState` y nombre de la restricción: el
mensaje de PostgreSQL ("Failing row contains (...)", "Key (email)=(...)") puede incluir datos
personales.

**Deuda conocida (Fase 2):** ~~las fechas de gastos e ingresos no tienen rango~~ (resuelto en D-047);
`GET /incomes` muestra también ingresos de ex-miembros (no cuentan en el reparto ni en el
resumen); una baja de miembro simultánea a un alta de gasto en la que es pagador no está
serializada; si un ex-miembro vuelve, sus ingresos recurrentes antiguos vuelven a contar.

## D-047 · Fechas de la API entre los años 1 y 9999 — Aceptada (Fase 3)
**Contexto:** QA de tareas comprobó que PostgreSQL guarda fechas a.C. pero el driver JDBC las lee
mal (`-0100-03-31` vuelve como `0101-03-31`; `0000-02-29` ni se puede leer y deja el listado del
hogar en 500) y que los años de 5 cifras fallan al insertar (409 `data-conflict`).
**Decisión:** `JacksonConfig` rechaza cualquier `LocalDate` de entrada fuera de
`0001-01-01..9999-12-31` como `validation-error` del campo, en toda la API (amplía D-045). Completar
una tarea cuya siguiente ocurrencia pasaría de 9999 → 422 `task-schedule-exhausted`: la API nunca
devuelve una fecha que ella misma rechazaría.
**Alternativas:** validar en cada DTO (repetitivo y fácil de olvidar en features nuevas).

## D-048 · Texto obligatorio con `@HasText` — Aceptada (Fase 3)
`@NotBlank` usa `trim()` (solo `<= U+0020`) y las entidades `String.isBlank()` (todo
`Character.isWhitespace`): un título de espacios Unicode pasaba la validación y la entidad lo
rechazaba con un 500. `shared.validation.HasText` aplica la semántica de `isBlank()` con un
patrón posesivo (coste lineal) y se usa en todos los textos obligatorios. Además
`PageResponse.MAX_PAGE` (`Integer.MAX_VALUE / 100`) acota `page` en todos los listados: por encima,
Spring Data fallaba con 500.

## D-049 · Periodicidad calculada desde una fecha ancla — Aceptada (Fase 3)
**Contexto:** sumar un mes a la fecha anterior deriva (31-ene → 28-feb → 28-mar…).
**Decisión:** la tarea guarda `anchor_date`; la ocurrencia `k` es `ancla + k·periodo` (k ≥ 0) y la
siguiente es la menor posterior a `max(dueDate, hoy)`. Completar tarde salta a la siguiente
futura (sin atrasos acumulados); completar antes avanza un periodo. El ancla solo cambia cuando un
PUT cambia `dueDate` o `recurrence`. Cálculo O(1) con `ChronoUnit` y ajuste, probado con 20 000
casos aleatorios por periodicidad.

## D-050 · Completar es idempotente frente a dobles toques — Aceptada (Fase 3)
`complete` bloquea la fila (como D-044) y acepta un `dueDate` opcional: si no coincide con la
ocurrencia actual → 409 `task-occurrence-mismatch`. Dos toques o dos miembros a la vez producen un
200 y un 409, nunca dos avances. Cualquier miembro activo puede completar (ayudar a otro): el
histórico guarda quién la hizo y quién estaba asignado.

## D-051 · Rotación sobre miembros activos, sin acoplar household → task — Aceptada (Fase 3)
Al completar, el siguiente asignado es el primer miembro **activo** después del actual en la lista
(circular); si no queda ninguno, la tarea queda sin asignar. Quien deja el hogar conserva sus tareas
asignadas hasta que alguien las edite o la rotación pase de largo: así la feature de hogares no
depende de la de tareas. Un PUT exige que asignado y rotación sean miembros activos (422).
La rotación se guarda en `task_rotation_members` con `UNIQUE(task_id, user_id)` diferido (D-038).

## D-052 · Orden `NULLS LAST` dentro de la `Specification` — Aceptada (Fase 3)
Spring Data JPA 3.5 no admite `Sort.nullsLast()` con consultas criteria. El listado de tareas
(`due_date ASC NULLS LAST, created_at, id`) fija el orden en la propia `Specification` con
`HibernateCriteriaBuilder`; el `Sort` del `Pageable` se ignora, como en gastos (D-039).

**Deuda conocida (Fase 3):** "hoy" y "vencida" en UTC (sin zona horaria por hogar); PUT sin
versión/`If-Match` (un PUT con datos viejos tras un `complete` re-ancla en la fecha antigua; mismo
diseño que gastos); PUT con `assigneeId` nulo en una tarea con rotación reinicia el turno a
`rotation[0]`; una baja de miembro simultánea a asignarle una tarea no está serializada.

## D-053 · Tipo de factura solo por magic bytes — Aceptada (Fase 4)
El `Content-Type` y la extensión los controla el cliente. Se leen los primeros bytes y solo se
admiten PDF (`%PDF-`), JPEG, PNG y WebP (`RIFF…WEBP`); cualquier otra cosa → 415
`unsupported-file-type` sin dejar nada en disco. El tipo guardado y servido es el detectado. La
descarga sale siempre como `attachment` con `X-Content-Type-Options: nosniff` y
`Cache-Control: private, no-store`: un polyglot PDF+HTML nunca se interpreta como HTML.

## D-054 · Almacenamiento direccionado por UUID tras una interfaz — Aceptada (Fase 4)
`StorageService` (`store`/`open`/`delete`) con `LocalFileSystemStorage` ahora y S3 posible sin tocar
servicios. La clave es un UUID canónico generado por la app (nada del cliente llega a la ruta); se
comprueba además que la ruta normalizada sigue dentro del directorio base. Escritura a temporal +
`ATOMIC_MOVE`. El nombre original solo se guarda saneado para `Content-Disposition` (RFC 6266,
`filename` + `filename*`), contado en puntos de código como `varchar(255)`.

## D-055 · Consistencia BD ↔ disco: antes un huérfano que una fila sin fichero — Aceptada (Fase 4)
**Decisión:** subir = escribir fichero → insertar fila; el fichero solo se borra si la transacción
hace **rollback** (no ante un resultado desconocido, porque la fila podría existir). Borrar factura o
gasto = borrar fila → borrar fichero en `afterCommit` (el gasto avisa con `ExpenseDeletedEvent`
síncrono, sin depender de facturas). Subir/borrar factura bloquea la fila del gasto
(`ExpenseQueries.lockForModification`), así se serializa con la edición y el borrado del gasto.
**Consecuencias:** un fallo al borrar un fichero tras el commit deja un huérfano (se registra en el
log); la limpieza periódica queda como deuda.

## D-056 · Factura y gasto del mismo hogar, garantizado por BD — Aceptada (Fase 4)
FK compuesta `invoices(expense_id, household_id) → shared_expenses(id, household_id)` (V10 añade
`UNIQUE(id, household_id)` en `shared_expenses` sin editar V8). Una factura no puede quedar
asociada a un hogar distinto del de su gasto ni por un bug del servicio.

## D-057 · Errores de multipart como ProblemDetail — Aceptada (Fase 4)
Multipart mal formado (cuerpo truncado, sin boundary, cabecera ilegible) → 400
`malformed-multipart` (antes 500 con traza ERROR por petición: un usuario podía llenar el log).
Exceso de tamaño → 413 `file-too-large`. `server.tomcat.max-part-header-size: 4KB`: con 512 B, un
nombre no ASCII largo daba un falso 413. La descarga lee los metadatos en una transacción de solo
lectura que se cierra antes de enviar el fichero (no retiene conexión a BD).

**Deuda conocida (Fase 4):** sin cuota de disco por hogar/usuario; el SHA-256 no se vuelve a
comprobar al descargar; sin limpieza periódica de huérfanos; la subida retiene la conexión y el
bloqueo del gasto mientras escribe (con S3 cruzaría la red: subir primero y enlazar después); un
borrado de gastos que no pase por `ExpenseService` (cascada futura de hogar/usuario) no publicaría
el evento.

## D-058 · Balances calculados, nunca guardados — Aceptada (Fase 5)
`balance = pagado − debido + liquidaciones enviadas − recibidas` (positivo = acreedor) se calcula en
cada petición con cuatro sumas agrupadas en BD (gastos, partes congeladas, enviadas, recibidas): no hay
saldo almacenado que pueda desincronizarse. `BalanceCalculator` comprueba Σ == 0 en código; si fallara
sería un bug (500), nunca un error del cliente. Los ex-miembros con movimientos siguen apareciendo
(`active = false`): sus deudas son del hogar.

## D-059 · Una sola foto de la BD para calcular balances — Aceptada (Fase 5)
**Contexto:** QA reprodujo 500 al leer balances mientras se creaban o editaban gastos: con READ
COMMITTED cada suma ve un instante distinto y Σ pagado ≠ Σ debido.
**Decisión:** `GET /balances` y `GET /settlements/suggested` usan
`@Transactional(readOnly = true, isolation = REPEATABLE_READ)`: todas las sumas ven el mismo
instante. En PostgreSQL una transacción de solo lectura en ese nivel nunca se aborta por
serialización. **Alternativas:** una única consulta con `UNION` (más difícil de mantener).

## D-060 · Sugerencias de pago voraces y deterministas — Aceptada (Fase 5)
El mayor deudor paga al mayor acreedor `min(deuda, crédito)` hasta saldar, en céntimos y con empates
por `userId` como texto (D-041). Da como mucho n−1 pagos (no siempre el mínimo absoluto, que es un
problema NP-difícil), nunca pagos a uno mismo, y aplicarlos salda el hogar (probado con 10 000 casos
y con escenarios aleatorios por API). El límite del importe de una liquidación es el de
`numeric(19,2)`, no el de un gasto: una sugerencia puede sumar varios gastos máximos.

## D-061 · Quién registra y borra liquidaciones — Aceptada (Fase 5)
Registrar: una de las dos partes u OWNER (403 `settlement-forbidden`), y **después** se comprueba que
las partes son o fueron miembros (422): una petición no autorizada no revela si un UUID estuvo en el
hogar. Se admite pagar de más (anota la realidad; el balance cambia de signo). Borrar: creador,
cualquiera de las partes u OWNER (el receptor puede anular un pago que no recibió); el borrado bloquea
la fila, así dos borrados simultáneos dan 204 + 404 en vez de 409.

**Deuda conocida (Fase 5):** liquidaciones inmutables (corregir = borrar y crear); un ex-miembro no
puede consultar su saldo (ya no es miembro); clientes que lean importes como `double` perderían
precisión por encima de ~9·10^13 €; los DTO con `@AssertTrue` (`distinctParties`, `endDateValid`…)
aparecen como propiedad al serializarse (inocuo en la entrada; a revisar con OpenAPI en la Fase 6).

## D-062 · Observabilidad con request id, logs ECS y métricas aparte — Aceptada (Fase 6)
**Decisión:** `RequestIdFilter` (antes de Spring Security) reutiliza un `X-Request-Id` válido
(`[A-Za-z0-9._-]{1,64}`) o genera un UUID; va a la respuesta, al MDC y a todo `ProblemDetail`
(`requestId`). Un valor no válido se sustituye, no se sanea (sin inyección en logs ni cabeceras). Logs
JSON en formato ECS fuera de `dev`/`test` (esquema abierto que leen Elastic y OpenTelemetry) y perfil
`docker` propio en Compose. Actuator (`health`, `info`, `metrics`, `prometheus`) en
`management.server.port` (8081), no publicado; las probes públicas son `/livez` y `/readyz`.
**Alternativas:** métricas en el puerto público tras JWT (Prometheus no se autentica con JWT).

## D-063 · Seguridad del puerto de gestión por puerto local — Aceptada (Fase 6)
Boot aplica la cadena de seguridad principal también al puerto de gestión. `ManagementSecurityConfig`
abre Actuator solo para conexiones que llegan al puerto de gestión, decidido por el **puerto local de
la conexión** (no falsificable con `Host` o `X-Forwarded-Port`) y solo si es distinto del puerto de la
API. QA final encontró que, con ambos puertos iguales, Boot publica `local.management.port` como alias
y la cadena abierta capturaba toda la API (sin JWT): ahora se exige que los puertos difieran, con IT
que arranca la app así.

## D-064 · Documentación OpenAPI verificada por test — Aceptada (Fase 6)
Cada endpoint lleva `summary`, parámetros y sus `code` de error; un `OpenApiCustomizer` añade 401/500
comunes, el esquema `ProblemDetail` y ejemplos por código sin repetir anotaciones. `OpenApiDocumentIT`
compara el documento con los mappings reales de Spring MVC en ambos sentidos: un endpoint nuevo sin
documentar rompe la build. Los métodos `@AssertTrue` de los DTO se ocultan (`@JsonIgnore` +
`@Schema(hidden)`); la validación y los nombres de campo de `errors[]` no cambian.

## D-065 · Listados de hogar acotados sin paginar — Aceptada (Fase 6)
`GET /api/households` usa un recuento agregado (3 consultas siempre; cierra la deuda de D-033). Los
miembros no se paginan por su tamaño natural (si hiciera falta un límite, sería un tope por plan
FREE/PREMIUM). Las invitaciones devuelven todas las vigentes (tope defensivo 100) y las 20 inactivas más
recientes: el historial dejaba de crecer sin límite sin cambiar la forma de la respuesta. El `Location`
del alta de una invitación apunta ahora a `GET …/invitations/{id}` (antes daba 405).

## D-066 · Contraseñas medidas en bytes UTF-8 y CORS sin configurar — Aceptada (Fase 6)
BCrypt solo admite 72 **bytes**; `@Size(max = 72)` cuenta caracteres y una contraseña con acentos daba
500. `@MaxUtf8Bytes(72)` lo convierte en `validation-error`. CORS no se configura: el cliente previsto es
una app nativa, y sin cabeceras CORS ningún navegador de otro origen puede leer respuestas.

**Deuda y plan de despliegue (Fase 6):** detrás de un proxy inverso hay que activar
`server.forward-headers-strategy=native` con `server.tomcat.remoteip.internal-proxies` limitado a la IP
del proxy; si no, todos los clientes comparten la IP del proxy y el cupo por IP del rate limit (D-023)
pasa a ser global (sin ello, rotar `X-Forwarded-For` no da cupos nuevos: probado). HSTS y CSP los debe
poner el proxy TLS (la app no ve `https` sin forward headers). Sin análisis de CVE automático en CI
(Dependabot / OWASP dependency-check). Rate limit por instancia. Sin borrado de cuenta (RGPD).

## D-067 · El reloj de la aplicación avanza en microsegundos — Aceptada (tras Fase 6)
**Contexto:** el primer CI en Linux falló: `Clock.systemUTC()` da nanosegundos allí (en macOS,
microsegundos) y PostgreSQL guarda `timestamptz` con microsegundos, así que el `createdAt` de la
respuesta de un alta no coincidía con el de un GET posterior. **Decisión:** el bean `Clock` es un
`MicrosecondClock` que trunca `systemUTC` a microsegundos: la API devuelve el mismo instante al crear y
al leer en cualquier SO. No vale `Clock.tick(systemUTC, 1 µs)`: su `millis()` divide entre cero y
rompía el rate limiter de login.
**Alternativas:** truncar en cada entidad (fácil de olvidar) o relajar los tests (oculta la
incoherencia de la API).

## D-068 · Plan freemium: límites de uso por hogar — Aceptada (Fase 7)
**Contexto:** el campo `plan` (FREE/PREMIUM) existía desde la Fase 1 sin efecto. No hay pagos: el plan
se cambia a mano (SQL) hasta integrar un proveedor. **Decisión:** FREE limita el uso, no las funciones:
- **4 miembros activos** por hogar: se comprueba al aceptar una invitación, con la fila del hogar ya
  bloqueada (la misma que serializa altas y bajas de miembros); el intento fallido no gasta un uso.
- **1 hogar FREE como OWNER** por usuario: se comprueba al crear, bajo un bloqueo consultivo de
  PostgreSQL por usuario (`pg_advisory_xact_lock`), porque no hay fila que bloquear para "hogares de
  este usuario". Ser MEMBER de otros hogares no cuenta. Ascender a OWNER a alguien no se comprueba
  (regla simple; el abuso no compensa con 4 miembros por hogar).
- **50 MB de facturas** por hogar: se comprueba tras copiar el fichero (el tamaño real solo se conoce
  en streaming) bloqueando el hogar **después** del gasto, siempre en ese orden para no crear
  interbloqueos; si no cabe, el rollback borra el fichero (D-049).
- **Historial de 12 meses naturales** en los listados de gastos y liquidaciones: el `from` efectivo es el
  más tardío entre el pedido y el inicio de la ventana. Los ingresos no se acotan (los recurrentes
  alimentan el reparto proporcional), el detalle por ID sigue accesible y los **balances usan todo el
  histórico**: ocultar datos nunca cambia lo que se debe.

Errores 422: `plan-member-limit`, `plan-household-limit`, `plan-storage-limit`. Pasar a FREE no borra
nada: solo bloquea lo nuevo que supere el límite. `GET /api/households/{id}/plan` devuelve plan, límites
(`null` = sin límite) y uso, para que el cliente muestre el estado antes de chocar con un 422.

**Diseño:** límites en configuración (`hogar.plans.free.*`), no en código ni en BD: se ajustan sin
migración. El uso de almacenamiento lo calcula invoice e implementa el puerto `HouseholdStorageUsage`
de household (inversión de dependencias: household no depende de invoice). Los IT generales usan
límites holgados; `PlanLimitsIT` arranca con los reales.
**Alternativas:** limitar funciones (sin facturas en FREE) — más simple pero menos realista; tabla de
planes en BD — prematuro sin pagos; contar hogares en el usuario — el plan es del hogar, no de la persona.
