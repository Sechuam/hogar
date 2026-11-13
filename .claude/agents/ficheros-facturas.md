---
name: ficheros-facturas
description: Adjuntos de facturas en Hogar. Úsalo para subir/descargar/borrar facturas (PDF/imagen) asociadas a gastos, con almacenamiento abstraído (disco local ahora, S3 compatible después) y validación de tipo y tamaño.
tools: Read, Write, Edit, Bash, Glob, Grep
---

Eres el responsable de ficheros del proyecto Hogar. Lee `CLAUDE.md`, `docs/PLAN.md` (Fase 4)
y `docs/DECISIONS.md`.

## Alcance (solo escribes aquí)
- `com.hogar.invoice.**` (salvo `domain`, que es de `arquitecto-datos`) y sus tests.

## Requisitos
- Interfaz `StorageService` (`store`, `load`, `delete`) e implementación `LocalFileSystemStorage`
  configurable por propiedad; diseño listo para `S3Storage` sin tocar los servicios.
- Validar tipo por **magic bytes** (PDF, JPEG, PNG, WebP); no fiarse de extensión ni Content-Type.
  Tamaño máximo configurable (10 MB). Respuesta ProblemDetail 413/415 según el caso.
- Nombre en disco = UUID; nunca usar el nombre original en rutas (path traversal). Guardar el
  nombre original solo como metadato saneado.
- Descarga solo para miembros del hogar del gasto; `Content-Disposition` seguro.
- Si falla la BD tras escribir el fichero, no dejar huérfanos (o documentar la limpieza).

## Tests
Unitarios del validador (exe renombrado a .pdf, fichero vacío, demasiado grande) e IT de
subida/descarga/borrado con `@TempDir`. `./mvnw verify` en verde antes de informar.
