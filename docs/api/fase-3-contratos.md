# Fase 3 — Contratos (tareas de casa)

Referencia para los agentes de la Fase 3. Enums y DTOs ya existen en el código
(`task.domain.TaskRecurrence`, `TaskStatus`; `task.dto.*`); aquí se fija el comportamiento.
Cambiar un contrato → avisar al coordinador.

## 1. Modelo de datos (dueño: `arquitecto-datos`) — migración `V9__tasks.sql`

| Tabla | Columnas clave | Restricciones / índices |
|---|---|---|
| `tasks` | `id uuid`, `household_id → households`, `title varchar(100)`, `description varchar(1000) NULL`, `assignee_id → users NULL`, `recurrence varchar(10)`, `due_date date NULL`, `anchor_date date NULL`, `status varchar(10)`, `created_by → users`, `last_completed_at timestamptz NULL`, `created_at`, `updated_at`, `version` | `CHECK` de enums; `CHECK recurrence = 'NONE' OR (due_date IS NOT NULL AND anchor_date IS NOT NULL)`; `CHECK status = 'PENDING' OR recurrence = 'NONE'`; índices `(household_id, status, due_date)`, `(household_id, assignee_id)`, FKs a users indexadas |
| `task_rotation_members` | `task_id → tasks ON DELETE CASCADE`, `position int`, `user_id → users` | `PK(task_id, position)`, `CHECK position >= 0`, `UNIQUE(task_id, user_id) DEFERRABLE INITIALLY DEFERRED`, índice `user_id` |
| `task_completions` | `id`, `task_id → tasks ON DELETE CASCADE`, `completed_by → users`, `assignee_id → users NULL`, `due_date date NULL`, `completed_at timestamptz` | índice `(task_id, completed_at DESC)`, FKs a users indexadas |

- `Task` es raíz de agregado (`@Version`). La rotación es `@ElementCollection` + `@OrderColumn(name = "position")`
  de `UUID` (LAZY, `@BatchSize(100)`). Las compleciones son **otra** entidad (`TaskCompletion`,
  sin relación JPA navegable desde `Task`: el histórico crece sin límite y se pagina aparte).
- `household_id`, `assignee_id`, `created_by` como UUID (D-017).
- `anchor_date` = `due_date` al crear o cuando un PUT cambia `dueDate`/`recurrence`; no cambia al completar.
- Repositorios: `TaskRepository.search(householdId, assigneeId?, status?, overdue?, today, pageable)` con
  `Specification` (D-039), orden fijo `due_date ASC NULLS LAST, created_at ASC, id ASC`;
  `findByIdAndHouseholdId`; `findForUpdate(id, householdId)` (`PESSIMISTIC_WRITE`, D-044).
  `TaskCompletionRepository.findByTaskIdOrderByCompletedAtDescIdDesc(taskId, pageable)`.

## 2. Lógica pura (dueño: `tareas-hogar`, paquete `task.domain.schedule`)

Clases sin Spring ni BD, deterministas, con tests unitarios exhaustivos.

`RecurrenceSchedule.nextDueDate(TaskRecurrence r, LocalDate anchor, LocalDate currentDue, LocalDate today)`
- `r != NONE` (si no → `IllegalArgumentException`). Ocurrencia `k` = `anchor + k·periodo`
  (`plusDays(k)`, `plusWeeks(k)`, `plusMonths(k)` **desde el ancla**: 31-ene → 28/29-feb → 31-mar).
- Devuelve la menor ocurrencia estrictamente posterior a `max(currentDue, today)`.
  Completar tarde **no** deja atrasos acumulados (se salta a la siguiente futura); completar antes
  de tiempo avanza un periodo desde `currentDue`.
- Coste O(1) aproximado (no iterar día a día durante años): estimar `k` con `ChronoUnit` y ajustar.

`TaskRotation.nextAssignee(List<UUID> rotation, UUID current, Set<UUID> activeMembers) → Optional<UUID>`
- Recorre la lista circularmente empezando **después** de `current` (si `current` no está en la lista,
  empieza por el principio) y devuelve el primer miembro activo. Puede volver a `current` si es el
  único activo. Ninguno activo → vacío (la tarea queda sin asignar).

## 3. Endpoints (dueño: `tareas-hogar`)

Todos bajo `/api/households/{householdId}`, autenticados. No miembro → 404 `household-not-found`
(`MembershipGuard`, **antes** de cualquier bloqueo, D-032). Tarea de otro hogar → 404 `task-not-found`.
Miembros activos y roles: `HouseholdQueries.getSplitInfo(householdId).activeMembers()`.
"Hoy" = `LocalDate.now(clock)` (UTC).

| Método y ruta | Quién | Cuerpo → respuesta | Reglas |
|---|---|---|---|
| `POST /tasks` | miembro | `TaskRequest` → 201 `TaskResponse` + `Location` | ver validaciones |
| `GET /tasks?mine=&assigneeId=&status=&overdue=&page=&size=` | miembro | → 200 `PageResponse<TaskResponse>` | `mine=true` = asignadas a mí; `mine` y `assigneeId` a la vez → 400 `conflicting-filters`; `overdue=true` = PENDING con `dueDate < hoy`, `false` = el resto; paginación como gastos (D-043) |
| `GET /tasks/{taskId}` | miembro | → 200 | |
| `PUT /tasks/{taskId}` | miembro | `TaskRequest` → 200 | tarea DONE → 409 `task-already-done` (reabrir antes); si cambian `dueDate` o `recurrence`, `anchor = dueDate` |
| `DELETE /tasks/{taskId}` | creador u OWNER | → 204 | si no → 403 `task-delete-forbidden`; borra histórico en cascada |
| `POST /tasks/{taskId}/complete` | miembro | `CompleteTaskRequest` opcional → 200 `TaskResponse` | ver compleción |
| `POST /tasks/{taskId}/reopen` | miembro | → 200 | solo tareas `NONE` en DONE; si no → 409 `task-not-done` |
| `GET /tasks/{taskId}/completions?page=&size=` | miembro | → 200 `PageResponse<TaskCompletionResponse>` | `completedAt desc` |

**Validaciones (alta y PUT)** — las de forma son Bean Validation (`validation-error`); las de estado, 422:
- `assigneeId` no miembro activo → 422 `assignee-not-member`.
- Algún miembro de `rotation` no activo → 422 `rotation-member-not-member`.
- Con rotación: `assigneeId` nulo → `rotation[0]`; `assigneeId` fuera de la rotación → 422 `assignee-not-in-rotation`.
- PUT puede quitar la rotación (lista vacía) o cambiarla.

**Compleción** (`complete`, con la fila bloqueada `findForUpdate`):
1. Si `request.dueDate` viene y difiere de la actual → 409 `task-occurrence-mismatch`.
2. `NONE` ya DONE → 409 `task-already-done`.
3. Se guarda `TaskCompletion(completedBy = usuario, assigneeId = asignado actual, dueDate = actual, completedAt = now)`
   y `lastCompletedAt = now`. Cualquier miembro activo puede completar (ayudar a otro).
4. `NONE` → `status = DONE`. Recurrente → `dueDate = RecurrenceSchedule.nextDueDate(...)`, sigue PENDING y,
   si hay rotación, `assigneeId = TaskRotation.nextAssignee(rotation, asignadoActual, activos).orElse(null)`.

`reopen` de una tarea `NONE` → PENDING (el histórico no se borra).

## 4. Fuera de alcance / notas
- Al abandonar el hogar un miembro, sus tareas siguen asignadas a él (se ve en la respuesta) hasta que
  alguien las edite o la rotación pase de largo. Sin dependencia household → task.
- Sin zona horaria por hogar: "hoy" y "vencida" se calculan en UTC (deuda para Fase 6/7).
