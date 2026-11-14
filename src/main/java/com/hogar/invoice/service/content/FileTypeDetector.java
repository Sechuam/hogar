package com.hogar.invoice.service.content;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;

/**
 * Detecta el tipo de un fichero <b>solo</b> por sus primeros bytes (magic bytes). El
 * {@code Content-Type} y la extensión que manda el cliente no se miran nunca.
 *
 * <ul>
 *   <li>PDF: {@code %PDF-}</li>
 *   <li>JPEG: {@code FF D8 FF}</li>
 *   <li>PNG: {@code 89 50 4E 47 0D 0A 1A 0A}</li>
 *   <li>WebP: {@code RIFF????WEBP}</li>
 * </ul>
 */
public final class FileTypeDetector {

    /** Bytes de cabecera necesarios para reconocer cualquiera de los tipos (la firma WebP es la más larga). */
    public static final int HEADER_LENGTH = 12;

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP = {'W', 'E', 'B', 'P'};

    private FileTypeDetector() {
    }

    /** Tipo a partir de la cabecera; vacío si no es uno de los admitidos o no hay bytes suficientes. */
    public static Optional<FileType> detect(byte[] header) {
        if (header == null) {
            return Optional.empty();
        }
        if (startsWith(header, 0, PDF)) {
            return Optional.of(FileType.PDF);
        }
        if (startsWith(header, 0, JPEG)) {
            return Optional.of(FileType.JPEG);
        }
        if (startsWith(header, 0, PNG)) {
            return Optional.of(FileType.PNG);
        }
        if (startsWith(header, 0, RIFF) && startsWith(header, 8, WEBP)) {
            return Optional.of(FileType.WEBP);
        }
        return Optional.empty();
    }

    /**
     * Lee la cabecera de {@code in} sin consumirla: el stream debe soportar {@code mark/reset}
     * (p. ej. un {@link java.io.BufferedInputStream}) y queda posicionado al principio.
     */
    public static Optional<FileType> detect(InputStream in) throws IOException {
        if (!in.markSupported()) {
            throw new IllegalArgumentException("Stream must support mark/reset");
        }
        in.mark(HEADER_LENGTH);
        byte[] header;
        try {
            header = in.readNBytes(HEADER_LENGTH);
        } finally {
            in.reset();
        }
        return detect(header);
    }

    private static boolean startsWith(byte[] data, int offset, byte[] signature) {
        return data.length >= offset + signature.length
                && Arrays.equals(data, offset, offset + signature.length, signature, 0, signature.length);
    }
}
