-- Hogares y membresías. Las membresías no se borran: abandonar/expulsar rellena left_at.

CREATE TABLE households (
    id           uuid         PRIMARY KEY,
    name         varchar(100) NOT NULL,
    split_policy varchar(20)  NOT NULL DEFAULT 'EQUAL',
    plan         varchar(20)  NOT NULL DEFAULT 'FREE',
    currency     char(3)      NOT NULL DEFAULT 'EUR',
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    version      bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_households_split_policy CHECK (split_policy IN ('EQUAL', 'PROPORTIONAL')),
    CONSTRAINT ck_households_plan CHECK (plan IN ('FREE', 'PREMIUM')),
    CONSTRAINT ck_households_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE memberships (
    id           uuid        PRIMARY KEY,
    household_id uuid        NOT NULL REFERENCES households (id),
    user_id      uuid        NOT NULL REFERENCES users (id),
    role         varchar(20) NOT NULL,
    joined_at    timestamptz NOT NULL,
    left_at      timestamptz,
    CONSTRAINT ck_memberships_role CHECK (role IN ('OWNER', 'MEMBER')),
    CONSTRAINT ck_memberships_left_after_joined CHECK (left_at IS NULL OR left_at >= joined_at)
);

-- Como mucho una membresía activa por usuario y hogar; las históricas (left_at) no cuentan,
-- así que volver a unirse crea una fila nueva.
CREATE UNIQUE INDEX uk_memberships_active_household_user
    ON memberships (household_id, user_id)
    WHERE left_at IS NULL;

CREATE INDEX ix_memberships_household_id ON memberships (household_id);
CREATE INDEX ix_memberships_user_id ON memberships (user_id);
