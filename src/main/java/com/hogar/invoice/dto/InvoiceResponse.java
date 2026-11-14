package com.hogar.invoice.dto;

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
public record InvoiceResponse(UUID id, UUID expenseId, String contentType, long sizeBytes, String filename,
                              String sha256, UUID uploadedBy, Instant createdAt) {
}
