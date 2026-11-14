# Fase 5 — Contratos (balances y liquidaciones)

Referencia para los agentes de la Fase 5. Ya existen en el código: DTOs de `settlement.dto`,
`ExpenseQueries.totalsByUser(householdId)` (+ `ExpenseTotals`, implementado con dos sumas agrupadas) y
`HouseholdQueries.isCurrentOrFormerMember(householdId, userId)`. Cambiar un contrato → avisar al coordinador.

La app **no mueve dinero**: una liquidación solo anota que alguien pagó a otro fuera de la app.

## 1. Modelo de datos (dueño: `arquitecto-datos`) — migración `V11__settlements.sql`

| Tabla | Columnas clave | Restricciones / índices |
|---|---|---|
| `settlements` | `id uuid`, `household_id → households`, `from_user_id → users`, `to_user_id → users`, `amount numeric(19,2)`, `currency char(3)`, `settlement_date date`, `note varchar(200) NULL`, `created_by → users`, `created_at timestamptz` | `CHECK amount > 0`, `CHECK from_user_id <> to_user_id`, `CHECK currency ~ '^[A-Z]{3}$'`, índice `(household_id, settlement_date DESC, created_at DESC, id DESC)`, FKs a users indexadas |

- Entidad inmutable `Settlement` (sin `@Version`; se borra y se vuelve a crear). IDs de aplicación (D-015),
  referencias por UUID (D-017). La moneda se copia del hogar al crear (como los gastos).
- Repositorio: `findByIdAndHouseholdId`; `search(householdId, from?, to?, userId?, pageable)` con
  `Specification` (D-039), `userId` = participa como pagador **o** receptor, orden fijo
  `settlement_date DESC, created_at DESC, id DESC`; `sumSentByUser(householdId)` y
  `sumReceivedByUser(householdId)` (proyección `userId`/`total`, agrupado).

## 2. Lógica pura (dueño: `finanzas`, paquete `settlement.domain.balance`)

`DebtSimplifier.simplify(Map<UUID, BigDecimal> balances) → List<Transfer(from, to, amount)>`
- Entrada: balances con escala ≤ 2 cuya suma es exactamente 0 (si no → `IllegalArgumentException`).
  Positivo = acreedor, negativo = deudor. Los 0 se ignoran.
- Voraz y **determinista**, en céntimos (`long`/`BigInteger`, sin redondeos): en cada paso el mayor
  deudor paga al mayor acreedor `min(|deuda|, crédito)`; empates por `userId` comparado como texto (D-041).
- Garantías (probadas con un test de propiedades): como mucho `n − 1` transferencias (n = balances ≠ 0);
  todas > 0; aplicarlas deja todos los balances a 0; nadie paga y cobra a la vez; misma entrada → misma salida
  (independiente del orden del `Map`).

`BalanceCalculator` (o equivalente, también puro): combina `ExpenseTotals` por usuario, enviados y recibidos
→ `balance = paid − owed + sent − received` (escala 2) y comprueba `Σ balance == 0` (si no, `IllegalStateException`:
sería un bug, nunca un error del cliente).

## 3. Endpoints (dueño: `finanzas`)

Todos bajo `/api/households/{householdId}`, autenticados. No miembro activo → 404 `household-not-found`
(`MembershipGuard`, D-032). Liquidación de otro hogar → 404 `settlement-not-found`.

| Método y ruta | Quién | Cuerpo → respuesta | Reglas |
|---|---|---|---|
| `GET /balances` | miembro | → 200 `BalanceResponse` | todos los gastos y liquidaciones del hogar; miembros activos siempre (orden de alta), después ex-miembros con algún importe ≠ 0 en pagado/debido/enviado/recibido (orden `userId`); `settled` = todos a 0 |
| `GET /settlements/suggested` | miembro | → 200 `SuggestedTransfersResponse` | `DebtSimplifier` sobre los balances actuales (incluye ex-miembros con saldo) |
| `POST /settlements` | miembro que es `from` o `to`, u OWNER | `SettlementRequest` → 201 `SettlementResponse` + `Location` | `from`/`to` deben ser miembros actuales **o antiguos** (`isCurrentOrFormerMember`) → si no, 422 `settlement-party-not-member`; si quien registra no es parte ni OWNER → 403 `settlement-forbidden`. Se permite pagar más de lo debido (anota la realidad; el balance cambia de signo) |
| `GET /settlements?from=&to=&userId=&page=&size=` | miembro | → 200 `PageResponse<SettlementResponse>` | `from > to` → 400 `invalid-date-range`; paginación como gastos (D-043, `MAX_PAGE`) |
| `GET /settlements/{settlementId}` | miembro | → 200 | |
| `DELETE /settlements/{settlementId}` | creador, cualquiera de las dos partes u OWNER | → 204 | si no → 403 `settlement-delete-forbidden` (el receptor puede anular un pago que no recibió) |

## 4. Notas
- Los balances se calculan siempre (no se guardan): no hay nada que desincronizar.
- Dejar el hogar con saldo pendiente está permitido; el ex-miembro sigue apareciendo en balances y
  sugerencias, y se le pueden registrar liquidaciones (no puede consultar el hogar: ya no es miembro).
- Escalas: todos los importes de la respuesta con escala 2 (`0.00`, nunca `0`).

## 5. Aclaraciones tras QA
- `amount` de una liquidación: de 0,01 a 99 999 999 999 999 999,99 (límite de `numeric(19,2)`), D-060.
- Balances y sugerencias se leen con una sola foto de la BD (REPEATABLE READ, D-059).
- Al crear: 403 `settlement-forbidden` antes que 422 `settlement-party-not-member` (D-061).
- Dos `DELETE` simultáneos de la misma liquidación: 204 + 404 `settlement-not-found`.
- Un ex-miembro saldado con movimientos sigue apareciendo con balance `0.00`.
