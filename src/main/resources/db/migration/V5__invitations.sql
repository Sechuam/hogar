-- Invitaciones a un hogar mediante código compartible.

CREATE TABLE invitations (
    id           uuid        PRIMARY KEY,
    household_id uuid        NOT NULL REFERENCES households (id),
    code         varchar(16) NOT NULL,
    created_by   uuid        NOT NULL REFERENCES users (id),
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    -- NULL = usos ilimitados hasta caducar.
    max_uses     integer,
    uses         integer     NOT NULL DEFAULT 0,
    revoked_at   timestamptz,
    CONSTRAINT uk_invitations_code UNIQUE (code),
    CONSTRAINT ck_invitations_uses CHECK (uses >= 0),
    CONSTRAINT ck_invitations_max_uses CHECK (max_uses IS NULL OR max_uses >= 1),
    -- Última barrera ante aceptaciones concurrentes: nunca más usos que los permitidos.
    CONSTRAINT ck_invitations_uses_within_max CHECK (max_uses IS NULL OR uses <= max_uses),
    CONSTRAINT ck_invitations_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_invitations_household_id ON invitations (household_id);
CREATE INDEX ix_invitations_created_by ON invitations (created_by);
