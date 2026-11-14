package com.hogar.invoice.service.content;

import com.hogar.invoice.service.FileTooLargeException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Envoltorio que, mientras otro consume el stream (p. ej. {@code StorageService.store}), cuenta los
 * bytes, calcula su SHA-256 y lanza {@link FileTooLargeException} en cuanto se lee más de
 * {@code maxBytes}. Nunca guarda el contenido en memoria.
 *
 * <p>No soporta {@code mark/reset} ni {@code skip} (los bytes saltados no se contarían en el hash).
 * El hash y el tamaño solo son definitivos cuando se ha leído hasta el final.
 */
public class MeteredInputStream extends FilterInputStream {

    private final long maxBytes;
    private final MessageDigest digest;
    private long count;

    public MeteredInputStream(InputStream in, long maxBytes) {
        super(in);
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
        this.digest = sha256();
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            digest.update((byte) b);
            advance(1);
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int n = super.read(buffer, offset, length);
        if (n > 0) {
            digest.update(buffer, offset, n);
            advance(n);
        }
        return n;
    }

    @Override
    public long skip(long n) {
        throw new UnsupportedOperationException("skip would bypass size and hash accounting");
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public synchronized void mark(int readLimit) {
        // no soportado: markSupported() == false
    }

    @Override
    public synchronized void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    /** Bytes leídos hasta ahora. */
    public long getCount() {
        return count;
    }

    /** SHA-256 en hexadecimal (minúsculas) de lo leído; llamar solo una vez, al terminar. */
    public String sha256Hex() {
        return HexFormat.of().formatHex(digest.digest());
    }

    private void advance(int n) {
        count += n;
        if (count > maxBytes) {
            throw new FileTooLargeException(maxBytes);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JVM", e);
        }
    }
}
