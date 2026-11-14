-- Tareas de casa: puntuales o recurrentes, con rotación opcional entre miembros e histórico de compleciones.

CREATE TABLE tasks (
    id                uuid          PRIMARY KEY,
    household_id      uuid          NOT NULL REFERENCES households (id),
    title             varchar(100)  NOT NULL,
    description       varchar(1000),
    -- NULL = sin asignar (o rotación sin ningún miembro activo).
    assignee_id       uuid          REFERENCES users (id),
    recurrence        varchar(10)   NOT NULL,
    -- Ocurrencia pendiente actual; en las recurrentes avanza al completarlas.
    due_date          date,
    -- Origen de las ocurrencias (anchor + k·periodo): se fija al crear o al cambiar due_date/recurrence
    -- y no cambia al completar, así una mensual del día 31 no deriva al 28.
    anchor_date       date,
    status            varchar(10)   NOT NULL,
    created_by        uuid          NOT NULL REFERENCES users (id),
    last_completed_at timestamptz,
    created_at        timestamptz   NOT NULL,
    updated_at        timestamptz   NOT NULL,
    version           bigint        NOT NULL DEFAULT 0,
    CONSTRAINT ck_tasks_title_not_blank CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_tasks_recurrence CHECK (recurrence IN ('NONE', 'DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT ck_tasks_status CHECK (status IN ('PENDING', 'DONE')),
    -- Una recurrente necesita fecha y ancla para calcular la siguiente ocurrencia.
    CONSTRAINT ck_tasks_recurring_has_dates
        CHECK (recurrence = 'NONE' OR (due_date IS NOT NULL AND anchor_date IS NOT NULL)),
    -- Una recurrente nunca queda DONE: al completarla pasa a la siguiente ocurrencia.
    CONSTRAINT ck_tasks_done_only_if_not_recurring CHECK (status = 'PENDING' OR recurrence = 'NONE'),
    -- El ancla es la due_date inicial y las ocurrencias solo avanzan: ambas existen o ninguna, y ancla ≤ due.
    CONSTRAINT ck_tasks_anchor_with_due CHECK ((anchor_date IS NULL) = (due_date IS NULL)),
    CONSTRAINT ck_tasks_anchor_not_after_due CHECK (anchor_date <= due_date)
);

-- Listado por hogar filtrando por estado y ordenando/filtrando por fecha (vencidas); su prefijo sirve a la FK household_id.
CREATE INDEX ix_tasks_household_status_due ON tasks (household_id, status, due_date);
-- Filtro "asignadas a" (mine / assigneeId).
CREATE INDEX ix_tasks_household_assignee ON tasks (household_id, assignee_id);
-- FKs a users (comprobaciones de borrado de usuarios y consultas por usuario fuera del hogar).
CREATE INDEX ix_tasks_assignee_id ON tasks (assignee_id);
CREATE INDEX ix_tasks_created_by ON tasks (created_by);

CREATE TABLE task_rotation_members (
    task_id  uuid    NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    -- Orden en la rotación (0..n-1), mantenido por Hibernate (@OrderColumn).
    position integer NOT NULL,
    user_id  uuid    NOT NULL REFERENCES users (id),
    -- La PK sirve también a la FK task_id.
    CONSTRAINT pk_task_rotation_members PRIMARY KEY (task_id, position),
    CONSTRAINT ck_task_rotation_members_position CHECK (position >= 0),
    -- Un usuario aparece una sola vez en la rotación de una tarea. DEFERRABLE INITIALLY DEFERRED: al
    -- reordenar la lista, Hibernate actualiza fila a fila (position → user_id) y un intercambio
    -- repetiría un usuario a mitad de flush; se comprueba al hacer commit, sobre el estado final (D-038).
    CONSTRAINT uk_task_rotation_members_task_user UNIQUE (task_id, user_id) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX ix_task_rotation_members_user_id ON task_rotation_members (user_id);

-- Histórico de compleciones: una fila por ocurrencia completada (no se edita).
CREATE TABLE task_completions (
    id           uuid        PRIMARY KEY,
    task_id      uuid        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    -- Quien la marcó como hecha (puede no ser el asignado).
    completed_by uuid        NOT NULL REFERENCES users (id),
    -- Asignado en ese momento (NULL si no había).
    assignee_id  uuid        REFERENCES users (id),
    -- Ocurrencia completada (NULL si la tarea no tenía fecha).
    due_date     date,
    completed_at timestamptz NOT NULL
);

-- Histórico paginado de una tarea (completed_at desc, id desc); su prefijo sirve a la FK task_id.
CREATE INDEX ix_task_completions_task_completed_at ON task_completions (task_id, completed_at DESC, id DESC);
CREATE INDEX ix_task_completions_completed_by ON task_completions (completed_by);
CREATE INDEX ix_task_completions_assignee_id ON task_completions (assignee_id);
