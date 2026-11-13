-- Gastos comunes y su reparto congelado (snapshot) entre los miembros.

CREATE TABLE shared_expenses (
    id                   uuid          PRIMARY KEY,
    household_id         uuid          NOT NULL REFERENCES households (id),
    paid_by              uuid          NOT NULL REFERENCES users (id),
    created_by           uuid          NOT NULL REFERENCES users (id),
    amount               numeric(19,2) NOT NULL,
    -- Copiada del hogar al crear el gasto: el gasto queda autocontenido.
    currency             char(3)       NOT NULL,
    description          varchar(200)  NOT NULL,
    category             varchar(30)   NOT NULL,
    expense_date         date          NOT NULL,
    split_policy_applied varchar(20)   NOT NULL,
    -- TRUE si el hogar era PROPORTIONAL pero nadie tenía ingresos: se repartió a partes iguales.
    split_fallback       boolean       NOT NULL DEFAULT false,
    created_at           timestamptz   NOT NULL,
    updated_at           timestamptz   NOT NULL,
    version              bigint        NOT NULL DEFAULT 0,
    CONSTRAINT ck_shared_expenses_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_shared_expenses_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_shared_expenses_category CHECK (category IN (
        'RENT', 'GROCERIES', 'ELECTRICITY', 'WATER', 'GAS', 'INTERNET', 'CLEANING', 'HOME_SUPPLIES', 'OTHER')),
    CONSTRAINT ck_shared_expenses_split_policy CHECK (split_policy_applied IN ('EQUAL', 'PROPORTIONAL')),
    -- El fallback siempre reparte a partes iguales.
    CONSTRAINT ck_shared_expenses_fallback_is_equal CHECK (NOT split_fallback OR split_policy_applied = 'EQUAL')
);

-- Listado por hogar en el orden de la API (fecha desc, alta desc); su prefijo sirve a la FK household_id.
CREATE INDEX ix_shared_expenses_household_date
    ON shared_expenses (household_id, expense_date DESC, created_at DESC);
CREATE INDEX ix_shared_expenses_household_category ON shared_expenses (household_id, category);
CREATE INDEX ix_shared_expenses_household_paid_by ON shared_expenses (household_id, paid_by);
-- FKs a users (comprobaciones de borrado de usuarios y consultas por usuario fuera del hogar).
CREATE INDEX ix_shared_expenses_paid_by ON shared_expenses (paid_by);
CREATE INDEX ix_shared_expenses_created_by ON shared_expenses (created_by);

CREATE TABLE expense_shares (
    id         uuid          PRIMARY KEY,
    expense_id uuid          NOT NULL REFERENCES shared_expenses (id) ON DELETE CASCADE,
    user_id    uuid          NOT NULL REFERENCES users (id),
    amount     numeric(19,2) NOT NULL,
    -- Base del reparto: ingreso del mes en PROPORTIONAL, 1 en EQUAL.
    weight     numeric(19,2) NOT NULL,
    CONSTRAINT ck_expense_shares_amount CHECK (amount >= 0),
    CONSTRAINT ck_expense_shares_weight CHECK (weight >= 0),
    -- Una parte por usuario y gasto; su índice sirve también a la FK expense_id.
    -- DEFERRABLE INITIALLY DEFERRED: al sustituir el reparto, Hibernate ejecuta los INSERT de las
    -- partes nuevas antes que los DELETE de las huérfanas (HHH-2801); la unicidad se comprueba al
    -- hacer commit, sobre el estado final.
    CONSTRAINT uk_expense_shares_expense_user UNIQUE (expense_id, user_id) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX ix_expense_shares_user_id ON expense_shares (user_id);
