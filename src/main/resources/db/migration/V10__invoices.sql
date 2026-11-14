-- Facturas adjuntas a gastos comunes: como mucho una por gasto. Solo metadatos: el contenido vive
-- en el almacenamiento de ficheros bajo storage_key. Inmutables: no se editan, se borran y se suben de nuevo.

-- Clave candidata (id, household_id) para que una factura no pueda apuntar a un gasto de otro hogar.
ALTER TABLE shared_expenses
    ADD CONSTRAINT uk_shared_expenses_id_household UNIQUE (id, household_id);

CREATE TABLE invoices (
    id           uuid         PRIMARY KEY,
    household_id uuid         NOT NULL REFERENCES households (id),
    expense_id   uuid         NOT NULL,
    -- Nombre del fichero en el almacenamiento (UUID generado por la aplicación, nunca el del cliente).
    storage_key  varchar(64)  NOT NULL,
    -- Tipo detectado por magic bytes, no el declarado por el cliente.
    content_type varchar(50)  NOT NULL,
    size_bytes   bigint       NOT NULL,
    -- SHA-256 del contenido en hexadecimal (minúsculas).
    sha256       char(64)     NOT NULL,
    -- Nombre original saneado: solo informativo (Content-Disposition al descargar).
    filename     varchar(255) NOT NULL,
    uploaded_by  uuid         NOT NULL REFERENCES users (id),
    created_at   timestamptz  NOT NULL,
    -- Borrar el gasto borra su factura; el fichero lo limpia la aplicación tras el commit.
    CONSTRAINT fk_invoices_expense FOREIGN KEY (expense_id, household_id)
        REFERENCES shared_expenses (id, household_id) ON DELETE CASCADE,
    -- Una factura por gasto; su índice sirve también a la FK compuesta (prefijo expense_id).
    CONSTRAINT uk_invoices_expense UNIQUE (expense_id),
    CONSTRAINT uk_invoices_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_invoices_storage_key_not_blank CHECK (btrim(storage_key) <> ''),
    CONSTRAINT ck_invoices_content_type CHECK (content_type IN (
        'application/pdf', 'image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT ck_invoices_size_positive CHECK (size_bytes > 0),
    CONSTRAINT ck_invoices_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- Sin caracteres de control, comillas dobles ni separadores de ruta (va en una cabecera HTTP).
    CONSTRAINT ck_invoices_filename CHECK (btrim(filename) <> '' AND filename !~ '[[:cntrl:]"/\\]')
);

CREATE INDEX ix_invoices_household_id ON invoices (household_id);
CREATE INDEX ix_invoices_uploaded_by ON invoices (uploaded_by);
