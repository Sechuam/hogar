package com.hogar.invoice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Metadatos de la factura adjunta a un gasto común (como mucho una por gasto). El contenido vive en
 * el almacenamiento de ficheros bajo {@link #getStorageKey()}.
 *
 * <p>Inmutable: no tiene {@code @Version} ni métodos de modificación; para cambiarla se borra y se
 * sube otra. Hogar, gasto y usuario se referencian por ID (D-017). Al borrar el gasto, la fila
 * desaparece por {@code ON DELETE CASCADE}.
 */
@Entity
@Immutable
@Table(name = "invoices")
public class Invoice implements Persistable<UUID> {

    /** Tipos admitidos (detectados por magic bytes); coinciden con {@code ck_invoices_content_type}. */
    public static final Set<String> SUPPORTED_CONTENT_TYPES =
            Set.of("application/pdf", "image/jpeg", "image/png", "image/webp");
    public static final int MAX_FILENAME_LENGTH = 255;
    static final int MAX_STORAGE_KEY_LENGTH = 64;

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    /** Caracteres de control, comillas dobles y separadores de ruta (igual que {@code ck_invoices_filename}). */
    private static final Pattern FORBIDDEN_FILENAME_CHARS = Pattern.compile("[\\p{Cntrl}\"/\\\\]");

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "expense_id", nullable = false, updatable = false)
    private UUID expenseId;

    @Column(name = "storage_key", nullable = false, length = MAX_STORAGE_KEY_LENGTH, updatable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 50, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 64, updatable = false)
    private String sha256;

    @Column(nullable = false, length = MAX_FILENAME_LENGTH, updatable = false)
    private String filename;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected Invoice() {
        // JPA
    }

    private Invoice(UUID householdId, UUID expenseId, String storageKey, String contentType, long sizeBytes,
            String sha256, String filename, UUID uploadedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.householdId = Objects.requireNonNull(householdId, "householdId");
        this.expenseId = Objects.requireNonNull(expenseId, "expenseId");
        this.storageKey = requireStorageKey(storageKey);
        this.contentType = requireContentType(contentType);
        this.sizeBytes = requirePositiveSize(sizeBytes);
        this.sha256 = requireSha256(sha256);
        this.filename = requireFilename(filename);
        this.uploadedBy = Objects.requireNonNull(uploadedBy, "uploadedBy");
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Registra la factura de un gasto cuyo contenido ya está guardado bajo {@code storageKey}.
     *
     * @param storageKey  clave en el almacenamiento (no vacía, como mucho 64 caracteres)
     * @param contentType uno de {@link #SUPPORTED_CONTENT_TYPES}
     * @param sizeBytes   &gt; 0
     * @param sha256      64 caracteres hexadecimales en minúsculas
     * @param filename    nombre ya saneado: no vacío, como mucho 255 caracteres, sin caracteres de
     *                    control, {@code "}, {@code /} ni {@code \}
     * @throws IllegalArgumentException si algún dato no es válido
     */
    public static Invoice create(UUID householdId, UUID expenseId, String storageKey, String contentType,
            long sizeBytes, String sha256, String filename, UUID uploadedBy, Instant now) {
        return new Invoice(householdId, expenseId, storageKey, contentType, sizeBytes, sha256, filename,
                uploadedBy, now);
    }

    private static String requireStorageKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey must not be blank");
        }
        if (storageKey.length() > MAX_STORAGE_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "storageKey must have at most " + MAX_STORAGE_KEY_LENGTH + " characters");
        }
        return storageKey;
    }

    private static String requireContentType(String contentType) {
        if (contentType == null || !SUPPORTED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("unsupported content type: " + contentType);
        }
        return contentType;
    }

    private static long requirePositiveSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        return sizeBytes;
    }

    private static String requireSha256(String sha256) {
        if (sha256 == null || !SHA256_HEX.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lower-case hexadecimal characters");
        }
        return sha256;
    }

    private static String requireFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename must not be blank");
        }
        // Puntos de código, como cuenta PostgreSQL (varchar) y como recorta FilenameSanitizer: con
        // length() (unidades UTF-16) un nombre saneado de más de 127 emojis acababa en un 500.
        if (filename.codePointCount(0, filename.length()) > MAX_FILENAME_LENGTH) {
            throw new IllegalArgumentException("filename must have at most " + MAX_FILENAME_LENGTH + " characters");
        }
        if (FORBIDDEN_FILENAME_CHARS.matcher(filename).find()) {
            throw new IllegalArgumentException("filename contains forbidden characters");
        }
        return filename;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public UUID getExpenseId() {
        return expenseId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getFilename() {
        return filename;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Invoice other && id != null && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Invoice[id=" + id + ", expenseId=" + expenseId + ", contentType=" + contentType + "]";
    }
}
