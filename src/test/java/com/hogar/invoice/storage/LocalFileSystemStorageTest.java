package com.hogar.invoice.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.invoice.service.FileTooLargeException;
import com.hogar.invoice.service.content.MeteredInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LocalFileSystemStorageTest {

    @TempDir
    Path tempDir;

    Path baseDir;
    LocalFileSystemStorage storage;

    @BeforeEach
    void setUp() {
        baseDir = tempDir.resolve("storage");
        storage = new LocalFileSystemStorage(baseDir);
    }

    @Test
    void createsTheBaseDirectoryIncludingParents() {
        Path nested = tempDir.resolve("a/b/c");

        LocalFileSystemStorage created = new LocalFileSystemStorage(nested);

        assertThat(nested).isDirectory();
        assertThat(created.getBaseDir()).isEqualTo(nested.toAbsolutePath().normalize());
    }

    @Test
    void failsFastWhenTheBaseDirectoryCannotBeCreated() throws IOException {
        Path file = Files.writeString(tempDir.resolve("not-a-dir"), "x");

        assertThatThrownBy(() -> new LocalFileSystemStorage(file.resolve("sub")))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void roundTrip() throws IOException {
        String key = UUID.randomUUID().toString();
        byte[] content = "%PDF-1.7 contenido".getBytes(StandardCharsets.UTF_8);

        storage.store(key, new ByteArrayInputStream(content));

        assertThat(baseDir.resolve(key)).hasBinaryContent(content);
        try (InputStream in = storage.open(key)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        assertThat(listBaseDir()).containsExactly(key);
    }

    @Test
    void roundTripOfALargerBinaryFile() throws IOException {
        String key = UUID.randomUUID().toString();
        byte[] content = new byte[3 * 1024 * 1024 + 17];
        new java.util.Random(42).nextBytes(content);

        storage.store(key, new ByteArrayInputStream(content));

        try (InputStream in = storage.open(key)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void storeOnAnExistingKeyFailsAndKeepsTheOriginal() throws IOException {
        String key = UUID.randomUUID().toString();
        storage.store(key, stream("original"));

        assertThatThrownBy(() -> storage.store(key, stream("otro")))
                .isInstanceOf(FileAlreadyExistsException.class);

        assertThat(baseDir.resolve(key)).hasContent("original");
        assertThat(listBaseDir()).containsExactly(key);
    }

    @Test
    void openingAMissingKeyIsFileNotFound() {
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void deleteRemovesTheFileAndIsIdempotent() throws IOException {
        String key = UUID.randomUUID().toString();
        storage.store(key, stream("x"));

        storage.delete(key);
        storage.delete(key);
        storage.delete(UUID.randomUUID().toString());

        assertThat(baseDir.resolve(key)).doesNotExist();
        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(FileNotFoundException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "../x", "..", ".", "x", "../" + "11111111-2222-3333-4444-555555555555",
            "11111111-2222-3333-4444-555555555555/../../etc/passwd",
            "/etc/passwd", "..\\x", "factura.pdf",
            "11111111-2222-3333-4444-55555555555", "11111111-2222-3333-4444-5555555555555",
            "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE",   // mayúsculas: no canónico
            "1-2-3-4-5",                              // UUID.fromString lo acepta; no es canónico
            " 11111111-2222-3333-4444-555555555555",
            "11111111-2222-3333-4444-555555555555\u0000"
    })
    void rejectsKeysThatAreNotCanonicalUuids(String key) {
        assertThatThrownBy(() -> storage.store(key, stream("x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(IllegalArgumentException.class);
        assertThat(listBaseDir()).isEmpty();
        assertThat(tempDir.resolve("x")).doesNotExist();
    }

    @Test
    void keysAreResolvedInsideTheBaseDirectory() {
        String key = UUID.randomUUID().toString();

        assertThat(storage.resolve(key)).isEqualTo(baseDir.toAbsolutePath().normalize().resolve(key));
    }

    @Test
    void failureWhileReadingLeavesNoTemporaryOrTargetFile() {
        String key = UUID.randomUUID().toString();
        InputStream failing = new InputStream() {
            int remaining = 10_000;

            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    throw new IOException("conexión cortada");
                }
                remaining--;
                return 'a';
            }
        };

        assertThatThrownBy(() -> storage.store(key, failing)).isInstanceOf(IOException.class).hasMessage("conexión cortada");

        assertThat(listBaseDir()).isEmpty();
    }

    @Test
    void sizeLimitExactlyAtTheMaximumIsStored() throws IOException {
        String key = UUID.randomUUID().toString();
        MeteredInputStream metered = new MeteredInputStream(new ByteArrayInputStream(new byte[4096]), 4096);

        storage.store(key, metered);

        assertThat(metered.getCount()).isEqualTo(4096);
        assertThat(Files.size(baseDir.resolve(key))).isEqualTo(4096);
    }

    @Test
    void sizeLimitPlusOneByteLeavesNothingOnDisk() {
        String key = UUID.randomUUID().toString();
        MeteredInputStream metered = new MeteredInputStream(new ByteArrayInputStream(new byte[4097]), 4096);

        assertThatThrownBy(() -> storage.store(key, metered)).isInstanceOf(FileTooLargeException.class);

        assertThat(listBaseDir()).isEmpty();
    }

    @Test
    void storedContentHashMatchesAKnownValue() throws IOException {
        String key = UUID.randomUUID().toString();
        MeteredInputStream metered = new MeteredInputStream(stream("abc"), 1024);

        storage.store(key, metered);

        assertThat(metered.sha256Hex()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(baseDir.resolve(key)).hasContent("abc");
    }

    @Test
    void temporaryFilesAreNeverValidKeys() throws IOException {
        Path stray = Files.createTempFile(baseDir, ".upload-", ".tmp");

        assertThatThrownBy(() -> storage.open(stray.getFileName().toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotFollowSymlinksWhenOpening() throws IOException {
        Path secret = Files.writeString(tempDir.resolve("secret.txt"), "secreto");
        String key = UUID.randomUUID().toString();
        try {
            Files.createSymbolicLink(baseDir.resolve(key), secret);
        } catch (UnsupportedOperationException | IOException e) {
            return; // sistema de ficheros sin enlaces simbólicos: nada que probar
        }

        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(IOException.class);
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> listBaseDir() {
        try (Stream<Path> files = Files.list(baseDir)) {
            return files.map(p -> p.getFileName().toString()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
