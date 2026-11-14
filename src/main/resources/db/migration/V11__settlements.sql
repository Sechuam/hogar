-- Liquidaciones: "from_user_id pagó amount a to_user_id" fuera de la app (Bizum, transferencia,
-- efectivo). La app no mueve dinero: solo lo anota para calcular balances. Inmutables: no se
-- editan, se borran y se registran de nuevo (sin version ni updated_at).
--
-- Sin FK a memberships: un ex-miembro puede ser parte (saldar al dejar el hogar o después). Que las
-- partes sean miembros actuales o antiguos lo comprueba el servicio.

CREATE TABLE settlements (
    id              uuid          PRIMARY KEY,
    household_id    uuid          NOT NULL REFERENCES households (id),
    from_user_id    uuid          NOT NULL REFERENCES users (id),
    to_user_id      uuid          NOT NULL REFERENCES users (id),
    amount          numeric(19,2) NOT NULL,
    -- Copiada del hogar al registrar la liquidación (como en los gastos).
    currency        char(3)       NOT NULL,
    settlement_date date          NOT NULL,
    -- Opcional; la aplicación la recorta y guarda NULL en vez de texto en blanco.
    note            varchar(200),
    created_by      uuid          NOT NULL REFERENCES users (id),
    created_at      timestamptz   NOT NULL,
    CONSTRAINT ck_settlements_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_settlements_distinct_parties CHECK (from_user_id <> to_user_id),
    CONSTRAINT ck_settlements_currency CHECK (currency ~ '^[A-Z]{3}$'),
    -- El driver JDBC lee mal las fechas a.C. y la API solo admite los años 1..9999 (D-047).
    CONSTRAINT ck_settlements_date_range CHECK (settlement_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    CONSTRAINT ck_settlements_note_not_blank CHECK (note IS NULL OR btrim(note) <> '')
);

-- Listado por hogar en el orden de la API (fecha desc, alta desc, id desc: orden total); su prefijo
-- sirve a la FK household_id y a las sumas agrupadas del hogar.
CREATE INDEX ix_settlements_household_date
    ON settlements (household_id, settlement_date DESC, created_at DESC, id DESC);
-- FKs a users (borrado de usuarios y filtro "participa como pagador o receptor").
CREATE INDEX ix_settlements_from_user_id ON settlements (from_user_id);
CREATE INDEX ix_settlements_to_user_id ON settlements (to_user_id);
CREATE INDEX ix_settlements_created_by ON settlements (created_by);
