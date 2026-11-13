-- Distingue expulsión de abandono: removed_by = OWNER que expulsó (NULL si el usuario se fue).
-- Permite impedir que un expulsado reutilice una invitación anterior a su expulsión (D-028).
ALTER TABLE memberships
    ADD COLUMN removed_by uuid REFERENCES users (id);

ALTER TABLE memberships
    ADD CONSTRAINT ck_memberships_removed_implies_left CHECK (removed_by IS NULL OR left_at IS NOT NULL);

CREATE INDEX ix_memberships_removed ON memberships (household_id, user_id) WHERE removed_by IS NOT NULL;
