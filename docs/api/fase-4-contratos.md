# Fase 4 — Contratos (facturas adjuntas)

Referencia para los agentes de la Fase 4. Ya existen en el código: `invoice.dto.InvoiceResponse`,
`invoice.storage.StorageService`, y en gastos `expense.service.ExpenseQueries` (+ implementación),
`expense.dto.ExpenseRef` y `expense.dto.ExpenseDeletedEvent` (publicado por `ExpenseService.delete`).
`GlobalExceptionHandler` ya traduce `MaxUploadSizeExceededException` → 413 `file-too-large`.
Cambiar un contrato → avisar al coordinador.

## 1. Modelo de datos (dueño: `arquitecto-datos`) — migración `V10__invoices.sql`

> La Fase 3 usa `V9`; esta fase usa `V10`. Se integra primero la Fase 3 en `main`.

| Tabla | Columnas clave | Restricciones / índices |
|---|---|---|
| `invoices` | `id uuid`, `household_id → households`, `expense_id → shared_expenses ON DELETE CASCADE`, `storage_key varchar(64)`, `content_type varchar(50)`, `size_bytes bigint`, `sha256 char(64)`, `filename varchar(255)`, `uploaded_by → users`, `created_at timestamptz` | `UNIQUE(expense_id)` (una factura por gasto), `UNIQUE(storage_key)`, `CHECK size_bytes > 0`, `CHECK content_type IN (...)` de los 4 tipos, `CHECK sha256 ~ '^[0-9a-f]{64}$'`, índices `household_id`, `uploaded_by` |

- Entidad `Invoice` inmutable (sin `@Version`: no se edita; se borra y se vuelve a subir).
  `household_id`, `expense_id`, `uploaded_by` como UUID (D-017).
- Repositorio: `findByExpenseIdAndHouseholdId`, `existsByExpenseId`, `findByExpenseId`.

## 2. Almacenamiento y validación (dueño: `ficheros-facturas`)

- `LocalFileSystemStorage implements StorageService`, directorio `hogar.storage.local.base-dir`
  (`STORAGE_DIR`, por defecto `./storage`; en tests `target/test-storage`). La clave debe ser un UUID
  canónico (si no → `IllegalArgumentException`); se resuelve dentro del directorio base y se comprueba que
  la ruta normalizada sigue dentro (defensa contra *path traversal*). Escritura en fichero temporal +
  `move` atómico; `store` sobre clave existente falla. Crea el directorio base si no existe.
- `InvoiceProperties` (`hogar.invoices.max-size`, `DataSize`, por defecto 10 MB).
- Detección de tipo **solo por magic bytes** (clase pura con tests): PDF `%PDF-`; JPEG `FF D8 FF`;
  PNG `89 50 4E 47 0D 0A 1A 0A`; WebP `RIFF????WEBP`. El `Content-Type` y la extensión del cliente se
  ignoran. Otro contenido → 415 `unsupported-file-type`.
- Se procesa en streaming (sin cargar el fichero entero en memoria): se cuentan bytes y se calcula
  SHA-256 mientras se escribe. Más de `max-size` → 413 `file-too-large` (y no queda nada en disco).
  Fichero vacío → 400 `empty-file`. Falta la parte `file` → 400 (lo resuelve Spring).
- `filename` guardado: nombre original sin ruta, sin caracteres de control ni `"`/`\`/`/`, recortado a 255;
  vacío → `factura`.

## 3. Endpoints (dueño: `ficheros-facturas`)

Base: `/api/households/{householdId}/expenses/{expenseId}/invoice`, autenticados. Primero
`MembershipGuard` (no miembro → 404 `household-not-found`); gasto de otro hogar o inexistente →
404 `expense-not-found` (vía `ExpenseQueries`).

| Método y ruta | Quién | Petición → respuesta | Reglas |
|---|---|---|---|
| `POST …/invoice` | pagador, creador u OWNER | multipart, parte `file` → 201 `InvoiceResponse` + `Location: …/invoice` | `ExpenseQueries.lockForModification` (403 `expense-edit-forbidden`); ya hay factura → 409 `invoice-already-exists` |
| `GET …/invoice` | miembro | → 200 binario | `Content-Type` guardado; `Content-Disposition: attachment` con `filename`/`filename*` (RFC 6266, `ContentDisposition` de Spring); `Content-Length`; `Cache-Control: private, no-store`; `X-Content-Type-Options: nosniff`. Sin factura → 404 `invoice-not-found` |
| `GET …/invoice/metadata` | miembro | → 200 `InvoiceResponse` | sin factura → 404 `invoice-not-found` |
| `DELETE …/invoice` | pagador, creador u OWNER | → 204 | `lockForModification`; sin factura → 404 `invoice-not-found` |

## 4. Consistencia BD ↔ disco

- **Subida:** se escribe el fichero con clave nueva y después se inserta la fila. Se registra una
  `TransactionSynchronization`: si la transacción no hace commit (`afterCompletion` ≠ COMMITTED), se
  borra el fichero. Nunca queda una fila sin fichero.
- **Borrado de factura:** se borra la fila; el fichero se borra en `afterCommit` (un fallo ahí solo se
  registra en el log: queda un huérfano, nunca una fila sin fichero).
- **Borrado del gasto:** un `@EventListener` de `ExpenseDeletedEvent` (síncrono, dentro de la
  transacción del borrado) busca la factura y programa el borrado del fichero en `afterCommit`. La fila
  desaparece por `ON DELETE CASCADE`.
- Subida/borrado de factura y edición/borrado del gasto se serializan con el bloqueo de la fila del gasto.
- La limpieza periódica de huérfanos queda fuera de alcance (deuda anotada).
