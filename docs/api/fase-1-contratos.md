# Fase 1 — Contratos (datos + autenticación)

Documento de referencia para los agentes de la Fase 1. Los DTOs y las interfaces ya existen
en el código; aquí se fija el comportamiento. Cambiar un contrato → avisar al coordinador.

## 1. Modelo de datos (dueño: `arquitecto-datos`)

| Tabla | Columnas clave | Restricciones / índices |
|---|---|---|
| `users` | `id uuid`, `email varchar(254)` (guardado en minúsculas), `display_name varchar(100)`, `password_hash varchar(100) NULL` (usuarios solo-Google), `email_verified boolean`, `created_at`, `updated_at`, `version` | `UNIQUE(email)` |
| `user_identities` | `id`, `user_id → users`, `provider varchar(20)` (`GOOGLE`), `subject varchar(255)`, `email varchar(254)`, `created_at` | `UNIQUE(provider, subject)`, índice `user_id` |
| `refresh_tokens` | `id`, `user_id → users`, `family_id uuid`, `token_hash char(64)` (SHA-256 hex), `created_at`, `expires_at`, `revoked_at NULL`, `replaced_by_id uuid NULL` | `UNIQUE(token_hash)`, índices `user_id`, `family_id` |
| `households` | `id`, `name varchar(100)`, `split_policy varchar(20)` (`EQUAL`/`PROPORTIONAL`, def. `EQUAL`), `plan varchar(20)` (`FREE`/`PREMIUM`, def. `FREE`), `currency char(3)` (def. `EUR`), `created_at`, `updated_at`, `version` | `CHECK` en enums |
| `memberships` | `id`, `household_id → households`, `user_id → users`, `role varchar(20)` (`OWNER`/`MEMBER`), `joined_at`, `left_at NULL`, `removed_by NULL → users` (V6) | **único parcial** `(household_id, user_id) WHERE left_at IS NULL`; índices `user_id`, `household_id` |
| `invitations` | `id`, `household_id → households`, `code varchar(16)`, `created_by uuid → users`, `created_at`, `expires_at`, `max_uses int NULL`, `uses int def 0`, `revoked_at NULL` | `UNIQUE(code)`, índice `household_id`, `CHECK(uses >= 0)` |

- Las membresías **no se borran**: al abandonar/expulsar se rellena `left_at` (los gastos e
  historial de tareas futuros siguen referenciando a ese usuario). Volver a unirse crea fila nueva.
- Entidades: `user.domain.{User, UserIdentity}`, `auth.domain.RefreshToken`,
  `household.domain.{Household, Membership, Invitation}`. Referencias entre features por UUID.
- Enums ya creados: `household.domain.{SplitPolicy, HouseholdPlan, HouseholdRole}`.

## 2. Endpoints de autenticación (dueño: `auth-seguridad`, rama A)

Todos devuelven `AuthResponse` salvo indicación. Públicos (ver `SecurityConfig.AUTH_PATHS`).

| Método y ruta | Cuerpo | Éxito | Errores (`code`) |
|---|---|---|---|
| `POST /api/auth/register` | `RegisterRequest` | 201 | 400 `validation-error`, 409 `email-already-registered`, 429 `rate-limited` |
| `POST /api/auth/login` | `LoginRequest` | 200 | 401 `invalid-credentials` (mismo mensaje si el email no existe o es usuario solo-Google), 429 |
| `POST /api/auth/google` | `GoogleLoginRequest` | 200 | 401 `invalid-google-token`, 401 `google-email-not-verified`, 429 |
| `POST /api/auth/refresh` | `RefreshTokenRequest` | 200 | 401 `invalid-refresh-token` (desconocido, caducado, revocado o **reutilizado** → revoca la familia) |
| `POST /api/auth/logout` | `RefreshTokenRequest` | 204 | 204 siempre (idempotente, no revela nada) |
| `GET /api/users/me` | — | 200 `UserResponse` | 401 |

- Access token: HS256 con `JwtEncoder` (bean de `shared.security.JwtConfig`), claims `iss`
  (`hogar.security.jwt.issuer`), `sub` = userId, `iat`, `exp` (`access-token-ttl`). Sin roles:
  la autorización es por hogar.
- Google (`POST /api/auth/google`): mismo email verificado que una cuenta existente → se
  vincula (`user_identities`) y se entra en esa cuenta. Si el email de esa cuenta **no estaba
  verificado**, se elimina su contraseña y se revocan sus sesiones (D-021). Si no existe → se crea usuario sin
  contraseña. Si `sub` de Google ya está vinculado → se entra en su usuario aunque el email
  de Google haya cambiado.
- Rate limiting (`hogar.security.rate-limit.*`) en register/login/google por clave IP + email
  normalizado (en google, solo IP). Exceso → `TooManyRequestsException("rate-limited", …)`.

## 3. Endpoints de hogares (dueño: `auth-seguridad`, rama B)

Todos autenticados. `{id}` de un hogar donde no eres miembro activo → **404 `household-not-found`**.

| Método y ruta | Rol | Cuerpo → respuesta | Reglas / errores |
|---|---|---|---|
| `POST /api/households` | — | `CreateHouseholdRequest` → 201 `HouseholdResponse` + `Location` | creador = OWNER |
| `GET /api/households` | — | → 200 `List<HouseholdResponse>` | solo hogares con membresía activa |
| `GET /api/households/{id}` | miembro | → 200 `HouseholdResponse` | |
| `PATCH /api/households/{id}` | OWNER | `UpdateHouseholdRequest` → 200 | 403 `owner-role-required` |
| `GET /api/households/{id}/members` | miembro | → 200 `List<MemberResponse>` | ordenados por `joinedAt` |
| `PATCH /api/households/{id}/members/{userId}` | OWNER | `UpdateMemberRoleRequest` → 200 `MemberResponse` | 422 `last-owner` si dejaría el hogar sin OWNER; 404 `member-not-found` |
| `DELETE /api/households/{id}/members/{userId}` | OWNER | → 204 | expulsar (queda registrado quién expulsó); no a uno mismo → 422 `use-leave-endpoint`; 404 `member-not-found` |
| `POST /api/households/{id}/leave` | miembro | → 204 | 422 `last-owner` si es el último OWNER y quedan más miembros (debe ceder el rol); si es el único miembro puede salir y el hogar queda sin miembros activos |
| `POST /api/households/{id}/invitations` | OWNER | `CreateInvitationRequest` (opcional) → 201 `InvitationResponse` | código aleatorio de 10 caracteres (alfabeto Crockford Base32, `SecureRandom`) |
| `GET /api/households/{id}/invitations` | OWNER | → 200 `List<InvitationResponse>` | |
| `DELETE /api/households/{id}/invitations/{invitationId}` | OWNER | → 204 | revoca (idempotente); 404 `invitation-not-found` si no es de este hogar |
| `POST /api/invitations/{code}/accept` | autenticado | → 200 `HouseholdResponse` | código normalizado (mayúsculas, sin guiones, O→0, I/L→1); 404 `invitation-not-found` (inexistente, revocada u hogar sin miembros), 422 `invitation-expired`, 422 `invitation-exhausted`, 409 `already-member`, 403 `removed-from-household` (expulsado después de crearse esa invitación, D-028); se une como MEMBER |

- `MembershipGuard` (en `shared.security`) lo implementa `household.service`.
- Contrato `user.service.UserDirectory` (lo implementa el coordinador) para nombres de miembros.
- Usar siempre el `Clock` inyectado.

## 4. Errores transversales

- Cuerpo con JSON roto → 400 `bad-request`; campo con tipo incorrecto o enum desconocido (también
  numérico) → 400 `validation-error` con `errors[]`.
- UUID mal formado en la ruta → 400 `bad-request`.
- Restricción de BD violada en una carrera → 409 `data-conflict`.
- Access token sin `exp`, con `sub` no UUID, otra clave, `alg: none` u otro `iss` → 401 `unauthorized`.
- JWKS de Google inaccesible → 503 `google-unavailable`.
- Rate limit: además del cupo IP + email, cupo por IP (`ip-capacity`) en registro y login.
