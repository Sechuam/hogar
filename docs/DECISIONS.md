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
