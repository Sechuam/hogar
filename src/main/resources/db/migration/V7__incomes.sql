-- Ingresos de los miembros de un hogar (base del reparto PROPORTIONAL).
-- Puntual: cuenta en el mes de start_date. Recurrente: cuenta cada mes desde start_date hasta
-- end_date (incluido su mes) o indefinidamente si end_date es NULL.

CREATE TABLE incomes (
    id           uuid          PRIMARY KEY,
    household_id uuid          NOT NULL REFERENCES households (id),
    user_id      uuid          NOT NULL REFERENCES users (id),
    amount       numeric(19,2) NOT NULL,
    concept      varchar(200)  NOT NULL,
    start_date   date          NOT NULL,
    recurring    boolean       NOT NULL,
    end_date     date,
    created_at   timestamptz   NOT NULL,
    updated_at   timestamptz   NOT NULL,
    version      bigint        NOT NULL DEFAULT 0,
    CONSTRAINT ck_incomes_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_incomes_end_date CHECK (end_date IS NULL OR (recurring AND end_date >= start_date))
);

-- Listado/suma por hogar y mes; su prefijo también sirve a la FK household_id.
CREATE INDEX ix_incomes_household_start_date ON incomes (household_id, start_date);
CREATE INDEX ix_incomes_user_id ON incomes (user_id);
