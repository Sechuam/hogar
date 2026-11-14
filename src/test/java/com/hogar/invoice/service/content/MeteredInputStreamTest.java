package com.hogar.invoice.service.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.invoice.service.FileTooLargeException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MeteredInputStreamTest {

    /** SHA-256("abc"), vector de prueba de FIPS 180-2. */
    private static final String SHA256_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    /** SHA-256 de la entrada vacía. */
    private static final String SHA256_EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void copiesCountsAndHashes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        StreamCopier.CopyResult result = StreamCopier.copy(
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)), out, 10);

        assertThat(result.sizeBytes()).isEqualTo(3);
        assertThat(result.sha256()).isEqualTo(SHA256_ABC);
        assertThat(out.toString(StandardCharsets.US_ASCII)).isEqualTo("abc");
    }

    @Test
    void emptyInputGivesZeroBytesAndTheEmptyHash() throws IOException {
        StreamCopier.CopyResult result = StreamCopier.copy(
                new ByteArrayInputStream(new byte[0]), OutputStream.nullOutputStream(), 1);

        assertThat(result.sizeBytes()).isZero();
        assertThat(result.sha256()).isEqualTo(SHA256_EMPTY);
    }

    @Test
    void exactlyTheMaximumIsAccepted() throws IOException {
        byte[] data = new byte[1024];
        Arrays.fill(data, (byte) 7);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        StreamCopier.CopyResult result = StreamCopier.copy(new ByteArrayInputStream(data), out, 1024);

        assertThat(result.sizeBytes()).isEqualTo(1024);
        assertThat(out.toByteArray()).isEqualTo(data);
    }

    @Test
    void oneByteOverTheMaximumIsRejected() {
        byte[] data = new byte[1025];

        assertThatThrownBy(() -> StreamCopier.copy(new ByteArrayInputStream(data), OutputStream.nullOutputStream(), 1024))
                .isInstanceOfSatisfying(FileTooLargeException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("file-too-large");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(e.getMaxBytes()).isEqualTo(1024);
                });
    }

    @Test
    void abortsWithoutReadingTheWholeOversizedStream() {
        // "Infinito": si el límite no cortara a tiempo, el test no terminaría.
        CountingEndlessStream endless = new CountingEndlessStream();

        assertThatThrownBy(() -> StreamCopier.copy(endless, OutputStream.nullOutputStream(), 100_000))
                .isInstanceOf(FileTooLargeException.class);
        assertThat(endless.produced).isLessThan(100_000 + 64 * 1024);
    }

    @Test
    void singleByteReadsAreAlsoCountedAndHashed() throws IOException {
        MeteredInputStream metered = new MeteredInputStream(new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)), 3);

        assertThat(metered.read()).isEqualTo('a');
        assertThat(metered.read()).isEqualTo('b');
        assertThat(metered.read()).isEqualTo('c');
        assertThat(metered.read()).isEqualTo(-1);
        assertThat(metered.getCount()).isEqualTo(3);
        assertThat(metered.sha256Hex()).isEqualTo(SHA256_ABC);
    }

    @Test
    void singleByteReadOverTheLimitIsRejected() throws IOException {
        MeteredInputStream metered = new MeteredInputStream(new ByteArrayInputStream(new byte[2]), 1);

        assertThat(metered.read()).isZero();
        assertThatThrownBy(metered::read).isInstanceOf(FileTooLargeException.class);
    }

    @Test
    void skipAndMarkAreNotSupported() {
        MeteredInputStream metered = new MeteredInputStream(new ByteArrayInputStream(new byte[10]), 10);

        assertThat(metered.markSupported()).isFalse();
        metered.mark(5);
        assertThatThrownBy(() -> metered.skip(1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(metered::reset).isInstanceOf(IOException.class);
    }

    @Test
    void maximumMustBePositive() {
        InputStream in = new ByteArrayInputStream(new byte[0]);
        assertThatThrownBy(() -> new MeteredInputStream(in, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MeteredInputStream(in, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class CountingEndlessStream extends InputStream {
        long produced;

        @Override
        public int read() {
            produced++;
            return 0;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            produced += len;
            return len;
        }
    }
}
