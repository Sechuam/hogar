package com.hogar.invoice.service.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Copia en streaming con límite de tamaño y SHA-256 (ver {@link MeteredInputStream}). */
public final class StreamCopier {

    private StreamCopier() {
    }

    /** Resultado de una copia completa: bytes copiados y SHA-256 en hexadecimal. */
    public record CopyResult(long sizeBytes, String sha256) {
    }

    /**
     * Copia {@code in} en {@code out} hasta el final. Si se supera {@code maxBytes} lanza
     * {@link com.hogar.invoice.service.FileTooLargeException}; lo ya escrito en {@code out} es
     * responsabilidad de quien llama (p. ej. descartar el temporal). No cierra ninguno de los streams.
     */
    public static CopyResult copy(InputStream in, OutputStream out, long maxBytes) throws IOException {
        MeteredInputStream metered = new MeteredInputStream(in, maxBytes);
        metered.transferTo(out);
        return new CopyResult(metered.getCount(), metered.sha256Hex());
    }
}
