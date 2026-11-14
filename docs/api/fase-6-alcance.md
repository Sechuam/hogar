# Fase 6 — Alcance y reparto (pulido)

Sin features nuevas: documentación, observabilidad, rendimiento y revisión final. Los contratos de
las fases 1–5 no cambian salvo lo indicado aquí.

## Reparto (primera tanda, en paralelo)

| Bloque | Agente | Zona |
|---|---|---|
| A. Observabilidad | `devops-docs` | `shared/web/**` (filtro de request id), `shared/error/**` y `shared/security/ProblemDetailAuthenticationEntryPoint` (solo para añadir `requestId`), `application*.yml` (logging/management), `pom.xml` (Prometheus), `docker-compose.yml`, `Dockerfile` |
| B. OpenAPI | `devops-docs` | anotaciones en `*/controller/**` y `*/dto/**` de todas las features, `shared/config/OpenApiConfig` |
| C. N+1 y listados de hogares | `auth-seguridad` | `household/service/**`, `household/repository/**` |

Segunda tanda: README (arquitectura, decisiones, diagrama ER) y colección `http/*.http` (`devops-docs`).
Tercera: revisión final de `qa-tests` (cobertura, seguridad, casos límite).

## Reglas fijadas por el coordinador

**Request id (A)**
- Cabecera `X-Request-Id`: si la petición trae una válida (`[A-Za-z0-9._-]{1,64}`) se reutiliza; si no,
  se genera un UUID. Siempre se devuelve en la respuesta y va al MDC como `requestId` durante la petición
  (se limpia al terminar). Filtro con la mayor prioridad, antes de Spring Security (los 401 también la llevan).
- Todo `ProblemDetail` incluye la propiedad `requestId`.
- Logs: perfil `dev` legible para humanos; cualquier otro perfil (Docker, producción) JSON estructurado
  (`logging.structured.format.console`, soporte nativo de Boot 3.4+) con `requestId`.
- Actuator: `health`, `info`, `metrics`, `prometheus` (`micrometer-registry-prometheus`). Las métricas no se
  exponen en el puerto público: `management.server.port` aparte (`MANAGEMENT_PORT`, 8081 por defecto), no
  publicado en `docker-compose.yml`. `health` (con probes) sigue accesible en el puerto público para el
  healthcheck. Probar que `/actuator/prometheus` no responde en el puerto público.

**OpenAPI (B)**
- `@Tag` por recurso, `@Operation(summary, description)` en cada endpoint, respuestas de error con el
  esquema `ProblemDetail` y sus `code` posibles, ejemplos en los DTO (`@Schema(example = …)`), importes
  como `string`/`number` con escala 2 documentada, multipart y descarga binaria de facturas bien descritas.
- Los métodos `@AssertTrue` de los DTO (`isEndDateValid`, `isDistinctParties`, …) no deben aparecer ni en
  el JSON de salida ni en el esquema (`@JsonIgnore` + `@Schema(hidden = true)`); la validación sigue igual.
- Endpoints públicos (`/api/auth/**`) sin requisito de seguridad en el documento.
- Un IT que descargue `/v3/api-docs` y compruebe: todas las rutas, esquema bearer, ningún campo
  `*Valid`/`distinctParties`, y que cada operación documenta al menos un error.

**Listados (C)**
- `GET /api/households` sin N+1 (una consulta para los recuentos de miembros), con test que cuente sentencias.
- Revisar que ningún listado devuelve colecciones sin límite; si alguno lo hace y no merece paginarse
  (p. ej. miembros, por tamaño natural), justificarlo en el informe para `DECISIONS.md`.
