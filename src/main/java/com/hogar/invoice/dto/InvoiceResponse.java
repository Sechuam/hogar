package com.hogar.invoice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Metadatos de la factura adjunta a un gasto (el contenido se descarga aparte).
 *
 * @param contentType tipo detectado por magic bytes ({@code application/pdf}, {@code image/jpeg},
 *                    {@code image/png} o {@code image/webp}), nunca el que declaró el cliente
 * @param filename    nombre original saneado, solo informativo (en disco se guarda con un UUID)
 * @param sha256      hash del contenido en hexadecimal (integridad / deduplicación futura)
 */
@Schema(description = "Metadatos de la factura adjunta a un gasto (el contenido se descarga aparte).")
public record InvoiceResponse(
        @Schema(description = "ID de la factura.", example = "3f6c1d2e-8a4b-4c5d-9e7f-0a1b2c3d4e5f")
        UUID id,
        @Schema(description = "Gasto al que pertenece.", example = "c4d5e6f7-1a2b-4c3d-8e9f-5a6b7c8d9e0f")
        UUID expenseId,
        @Schema(description = "Tipo detectado por el contenido (magic bytes), nunca el declarado por el cliente.",
                example = "application/pdf",
                allowableValues = {"application/pdf", "image/jpeg", "image/png", "image/webp"})
        String contentType,
        @Schema(description = "Tamaño en bytes.", example = "184213")
        long sizeBytes,
        @Schema(description = "Nombre original saneado; solo informativo.", example = "ticket-mercadona.pdf")
        String filename,
        @Schema(description = "SHA-256 del contenido en hexadecimal.",
                example = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
        String sha256,
        @Schema(description = "Quién la subió.", example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        UUID uploadedBy,
        @Schema(description = "Subida (UTC).", example = "2026-03-15T09:30:00Z")
        Instant createdAt) {
}
