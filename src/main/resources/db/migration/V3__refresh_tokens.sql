-- Refresh tokens opacos y rotatorios. Solo se guarda el SHA-256 (hex) del token.

CREATE TABLE refresh_tokens (
    id             uuid        PRIMARY KEY,
    user_id        uuid        NOT NULL REFERENCES users (id),
    -- Todos los tokens nacidos de un mismo login comparten familia; reutilizar uno ya rotado
    -- revoca la familia entera.
    family_id      uuid        NOT NULL,
    token_hash     char(64)    NOT NULL,
    created_at     timestamptz NOT NULL,
    expires_at     timestamptz NOT NULL,
    revoked_at     timestamptz,
    -- Token que sustituyó a este al rotar (traza; sin FK para no bloquear la purga de caducados).
    replaced_by_id uuid,
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family_id ON refresh_tokens (family_id);
