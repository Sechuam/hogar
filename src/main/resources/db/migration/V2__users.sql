-- Usuarios y sus identidades externas (login social).

CREATE TABLE users (
    id             uuid         PRIMARY KEY,
    email          varchar(254) NOT NULL,
    display_name   varchar(100) NOT NULL,
    -- NULL para usuarios que solo entran con un proveedor externo (Google).
    password_hash  varchar(100),
    email_verified boolean      NOT NULL DEFAULT false,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    version        bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uk_users_email UNIQUE (email),
    -- La app normaliza a minúsculas; la BD lo garantiza para que UNIQUE no tenga huecos.
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email))
);

CREATE TABLE user_identities (
    id         uuid         PRIMARY KEY,
    user_id    uuid         NOT NULL REFERENCES users (id),
    provider   varchar(20)  NOT NULL,
    -- Identificador estable del usuario en el proveedor ('sub' del ID token).
    subject    varchar(255) NOT NULL,
    -- Email que reportó el proveedor al vincular (informativo; puede cambiar en origen).
    email      varchar(254) NOT NULL,
    created_at timestamptz  NOT NULL,
    CONSTRAINT uk_user_identities_provider_subject UNIQUE (provider, subject),
    CONSTRAINT ck_user_identities_provider CHECK (provider IN ('GOOGLE'))
);

CREATE INDEX ix_user_identities_user_id ON user_identities (user_id);
