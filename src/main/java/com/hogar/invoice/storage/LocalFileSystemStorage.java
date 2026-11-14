package com.hogar.invoice.storage;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * {@link StorageService} sobre un directorio local plano: cada clave es un fichero
 * {@code <baseDir>/<uuid>}.
 *
 * <ul>
 *   <li>La clave tiene que ser un UUID en forma canónica (minúsculas, con guiones); cualquier otra
 *       cosa es {@link IllegalArgumentException}. Además, la ruta normalizada se comprueba contra el
 *       directorio base (defensa en profundidad contra <i>path traversal</i>).</li>
 *   <li>La escritura va a un temporal del mismo directorio ({@code .upload-*.tmp}, que nunca es una
 *       clave válida) y se publica con un {@code move} atómico: un lector nunca ve un fichero a medias.
 *       Si algo falla a mitad (incluida una excepción lanzada por el propio stream de entrada, p. ej.
 *       al superar el tamaño máximo), el temporal se borra.</li>
 *   <li>{@code store} sobre una clave existente lanza {@link FileAlreadyExistsException}. La
 *       comprobación es previa al {@code move} (en POSIX un rename atómico reemplaza el destino); como
 *       las claves son UUID aleatorios generados por la aplicación, la carrera no es alcanzable.</li>
 * </ul>
 */
public class LocalFileSystemStorage implements StorageService {

    private static final String TEMP_PREFIX = ".upload-";
    private static final String TEMP_SUFFIX = ".tmp";

    private final Path baseDir;

    /** Crea el directorio base si no existe; si no se puede, falla al arrancar. */
    public LocalFileSystemStorage(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage directory " + this.baseDir, e);
        }
    }

    public Path getBaseDir() {
        return baseDir;
    }

    @Override
    public void store(String key, InputStream content) throws IOException {
        Path target = resolve(key);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(key);
        }
        Path temp = Files.createTempFile(baseDir, TEMP_PREFIX, TEMP_SUFFIX);
        try {
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                content.transferTo(out);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        Path path = resolve(key);
        try {
            return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            FileNotFoundException notFound = new FileNotFoundException(key);
            notFound.initCause(e);
            throw notFound;
        }
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    /** Ruta del fichero de una clave; solo acepta UUID canónicos que resuelvan dentro de {@code baseDir}. */
    Path resolve(String key) {
        if (key == null || !isCanonicalUuid(key)) {
            throw new IllegalArgumentException("Storage key must be a canonical UUID");
        }
        Path path = baseDir.resolve(key).normalize();
        if (!baseDir.equals(path.getParent())) {
            throw new IllegalArgumentException("Storage key resolves outside the storage directory");
        }
        return path;
    }

    private static boolean isCanonicalUuid(String key) {
        try {
            return UUID.fromString(key).toString().equals(key);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
