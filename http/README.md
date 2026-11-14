# Colección de peticiones (`http/`)

Flujo completo de la API para el cliente HTTP de IntelliJ IDEA (también funciona en el
[CLI `ijhttp`](https://www.jetbrains.com/help/idea/http-client-cli.html)). Cada fichero encadena
peticiones reales: guarda tokens e IDs de las respuestas en variables globales y comprueba el código
esperado con `client.test(...)`, así que la colección sirve también como prueba de humo.

| Orden | Fichero | Qué recorre |
|---|---|---|
| 1 | `auth.http` | probes, registro de Ana, Luis y Marta, login, `X-Request-Id`, refresh con rotación y detección de reutilización, logout |
| 2 | `households.http` | hogar PROPORTIONAL, 404 a quien no es miembro, invitación y aceptación, roles (403 / 422 `last-owner`) |
| 3 | `finances.http` | ingresos, resumen mensual, gastos con reparto proporcional, fallback a EQUAL, snapshot al cambiar la política, validación |
| 4 | `invoices.http` | subida de `factura-ejemplo.pdf`, metadatos, descarga, 409, fichero disfrazado (415), permisos |
| 5 | `tasks.http` | tarea semanal con rotación, compleción idempotente (409 al repetir), histórico, reabrir |
| 6 | `balances.http` | balances, pagos sugeridos, registrar liquidaciones hasta saldar el hogar, filtros y borrado |

`tasks.http` solo depende de `households.http`; el resto, de los anteriores.

## Uso en IntelliJ IDEA

1. Arranca la API (`docker compose up --build` o `./mvnw spring-boot:run`).
2. Abre `auth.http`, elige el entorno **local** (definido en `http-client.env.json`, `baseUrl`
   = `http://localhost:8080`) y pulsa *Run All Requests in File*. Repite con los demás ficheros en orden.
3. Los emails llevan `{{$timestamp}}`, así que el flujo se puede repetir contra la misma BD.
   Los access tokens duran 15 minutos: si caducan, relanza los tres `login` del final de `auth.http`.

Con el CLI: `ijhttp --env-file http-client.env.json --env local auth.http households.http finances.http invoices.http tasks.http balances.http`.

## Uso en VS Code (REST Client)

La extensión [REST Client](https://marketplace.visualstudio.com/items?itemName=humao.rest-client)
lee la misma sintaxis de peticiones, pero **no ejecuta** los bloques `> {% ... %}` (los ignora) ni lee
`http-client.env.json`. Equivalencias:

| IntelliJ | REST Client |
|---|---|
| `http-client.env.json` → `{"local": {"baseUrl": ...}}` | `settings.json` → `"rest-client.environmentVariables": {"local": {"baseUrl": "http://localhost:8080"}}` |
| `> {% client.global.set("anaToken", response.body.accessToken) %}` | `# @name anaLogin` encima de la petición y luego `{{anaLogin.response.body.$.accessToken}}` |
| `client.test(...)` | sin equivalente: revisa el código de estado a mano |
| `{{$timestamp}}` | `{{$timestamp}}` (igual) |
| `< ./factura-ejemplo.pdf` en multipart | igual |

Ejemplo de petición encadenada en REST Client:

```http
# @name anaLogin
POST {{baseUrl}}/api/auth/login
Content-Type: application/json

{"email": "ana@example.test", "password": "contrasena-de-prueba-1"}

###
GET {{baseUrl}}/api/users/me
Authorization: Bearer {{anaLogin.response.body.$.accessToken}}
```

## Notas

- Solo datos ficticios (`@example.test`, contraseñas de prueba). Ningún secreto: el `JWT_SECRET` vive
  en `.env`, que no se versiona.
- `factura-ejemplo.pdf` es un PDF 1.4 mínimo válido (una página): la API detecta el tipo por sus
  *magic bytes* (`%PDF-`), no por la extensión.
- Las métricas (`/actuator/prometheus`) están en el puerto de gestión 8081, que Docker Compose no publica:
  desde fuera solo responden `/livez` y `/readyz`.
