# Fase 2 — Contratos (ingresos, gastos y reparto)

Referencia para los agentes de la Fase 2. DTOs, interfaces y tipos del reparto ya existen en el
código; aquí se fija el comportamiento. Cambiar un contrato → avisar al coordinador.

## 1. Modelo de datos (dueño: `arquitecto-datos`)

| Tabla | Columnas clave | Restricciones / índices |
|---|---|---|
| `incomes` | `id uuid`, `household_id → households`, `user_id → users`, `amount numeric(19,2)`, `concept varchar(200)`, `start_date date`, `recurring boolean`, `end_date date NULL`, `created_at`, `updated_at`, `version` | `CHECK amount > 0`; `CHECK end_date IS NULL OR (recurring AND end_date >= start_date)`; índice `(household_id, start_date)`, índice `user_id` |
| `shared_expenses` | `id`, `household_id → households`, `paid_by → users`, `created_by → users`, `amount numeric(19,2)`, `currency char(3)`, `description varchar(200)`, `category varchar(30)`, `expense_date date`, `split_policy_applied varchar(20)`, `split_fallback boolean`, `created_at`, `updated_at`, `version` | `CHECK amount > 0`; `CHECK` de enums; índices `(household_id, expense_date DESC)`, `(household_id, category)`, `(household_id, paid_by)` |
| `expense_shares` | `id`, `expense_id → shared_expenses ON DELETE CASCADE`, `user_id → users`, `amount numeric(19,2)`, `weight numeric(19,2)` | `CHECK amount >= 0`, `CHECK weight >= 0`, `UNIQUE(expense_id, user_id)`, índice `user_id` |

- `SharedExpense` es raíz de agregado con sus `ExpenseShare` (`@OneToMany` cascade ALL +
  orphanRemoval, LAZY, `@BatchSize(100)`). Reemplazar el reparto = sustituir la colección completa
  con `replaceShares(SplitResult, Instant now)`. `UNIQUE(expense_id, user_id)` es
  `DEFERRABLE INITIALLY DEFERRED` (D-038).
- En las entidades la fecha se llama `startDate` (ingreso) y `expenseDate` (gasto); en la API, `date`.
- Repositorios: `IncomeRepository.search(householdId, userId?, month?, pageable)`,
  `sumByUserForMonth(householdId, month)`; `SharedExpenseRepository.search(householdId, from?, to?,
  category?, paidBy?, pageable)` y `findByIdAndHouseholdId` (con shares). Orden fijo; el `Sort` del
  `Pageable` se ignora.
- `Income`: `household_id`/`user_id` como UUID (D-017). `appliesTo(YearMonth)`: puntual → mes de
  `start_date`; recurrente → `YearMonth(start) <= m <= YearMonth(end)` (sin fin = indefinido).
- La moneda del gasto se **copia** del hogar al crearlo (el hogar no permite cambiarla, pero el
  gasto queda autocontenido). Los ingresos usan la del hogar (no se guarda por ingreso).

## 2. Reparto (dueño: `finanzas`, `expense.domain.split`)

`SplitCalculator.split(BigDecimal total, SplitPolicy policy, List<SplitParticipant> participants) → SplitResult`

Clase pura (sin Spring, sin BD), determinista:

1. `total` > 0 con escala ≤ 2; ≥ 1 participante; userIds únicos. Si no → `IllegalArgumentException`.
2. Orden de los participantes: `joinedAt` asc, luego `userId` asc. Las `shares` salen en ese orden.
3. Pesos: EQUAL → 1 para todos. PROPORTIONAL → `income`. Si la suma de ingresos es 0 →
   se aplica EQUAL y se añade `PROPORTIONAL_FALLBACK_NO_INCOME`. **Ingreso 0 con otros > 0 →
   paga 0 € (D-009).**
4. En céntimos y aritmética exacta: `base_i = ⌊total·w_i / W⌋`, `resto_i = total·w_i mod W`.
   Los céntimos sobrantes (`total − Σ base_i`, siempre < nº de pesos > 0) se dan de uno en uno a
   los mayores restos; empate → orden del punto 2. Un peso 0 nunca recibe céntimo.
5. Invariante comprobada en el propio código: `Σ amount == total`. `weight` se devuelve con escala 2.

## 3. Endpoints (dueño: `finanzas`)

Todos bajo `/api/households/{householdId}` y autenticados. No miembro → 404 `household-not-found`
(`MembershipGuard`). IDs de otro hogar → 404 del recurso (`income-not-found`, `expense-not-found`).

### Ingresos

| Método y ruta | Quién | Cuerpo → respuesta | Reglas |
|---|---|---|---|
| `POST /incomes` | miembro | `IncomeRequest` → 201 `IncomeResponse` + `Location` | el ingreso es **del usuario autenticado** |
| `GET /incomes?month=YYYY-MM&userId=&page=&size=` | miembro | → 200 `PageResponse<IncomeResponse>` | todos los del hogar (transparencia del reparto); `month` filtra los que aplican a ese mes; orden `date desc` |
| `GET /incomes/{incomeId}` | miembro | → 200 `IncomeResponse` | |
| `PUT /incomes/{incomeId}` | dueño del ingreso | `IncomeRequest` → 200 | otro miembro → 403 `income-owner-required` |
| `DELETE /incomes/{incomeId}` | dueño del ingreso | → 204 | ídem |
| `GET /incomes/summary?month=YYYY-MM` | miembro | → 200 `MonthlyIncomeSummary` | un elemento por miembro activo (también con 0); `percentage` suma 100,00 (mismo redondeo de mayor resto) o todo 0 si `total` = 0 |

### Gastos

| Método y ruta | Quién | Cuerpo → respuesta | Reglas |
|---|---|---|---|
| `POST /expenses` | miembro | `ExpenseRequest` → 201 `ExpenseResponse` + `Location` | `paidBy` null → el autenticado; `paidBy` no miembro activo → 422 `payer-not-member` |
| `GET /expenses?from=&to=&category=&paidBy=&page=&size=` | miembro | → 200 `PageResponse<ExpenseResponse>` | orden `date desc, createdAt desc`; `size` 1–100 y `page` ≥ 0 (si no, 400); `from > to` → 400 `invalid-date-range` |
| `GET /expenses/{expenseId}` | miembro | → 200 | |
| `PUT /expenses/{expenseId}` | pagador, creador u OWNER | `ExpenseRequest` → 200 | `paidBy` nulo conserva el pagador (D-042); si cambian `amount`, `date` o `paidBy` → **se recalcula** el reparto con la política, ingresos y miembros **actuales**; si solo cambian descripción/categoría, los shares no se tocan. Sin permiso → 403 `expense-edit-forbidden` |
| `DELETE /expenses/{expenseId}` | pagador, creador u OWNER | → 204 | |

- Participantes del reparto: miembros **activos en el momento de crear (o recalcular)** el gasto (D-034).
- PROPORTIONAL usa `IncomeQueries.monthlyIncomeByUser(householdId, YearMonth.from(date))`;
  los miembros sin ingresos ese mes participan con 0.
- `warnings` en la respuesta se derivan de `split_fallback` (persistido), así que también aparecen al consultar.
- `hasInvoice` indica si el gasto tiene factura adjunta (opcional, D-070); al crear siempre es `false`.
- Cambiar la `splitPolicy` del hogar o los ingresos **no** modifica gastos existentes (snapshot).

## 4. Aclaraciones tras QA

- `GET /incomes` lista todos los ingresos del hogar, también los de ex-miembros (histórico); el
  `summary` y el reparto solo cuentan miembros **activos**.
- Ediciones concurrentes del mismo gasto se serializan (D-044); si la BD aborta una por bloqueo,
  409 `concurrent-modification`.
- Texto con U+0000 o fecha numérica → 400 `validation-error` del campo (D-045). Importes con ceros
  de más (`12.340`) → 400: `@Digits` cuenta la escala tal cual llega.
- Importes en notación científica (`1e2`) o entre comillas (`"12.34"`) se aceptan si el valor es válido.
