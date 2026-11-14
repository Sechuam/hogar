package com.hogar.invoice.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * Almacén de ficheros binarios direccionado por clave opaca. La implementación actual es disco
 * local ({@code LocalFileSystemStorage}); una de S3 cumpliría el mismo contrato.
 * Las claves las genera la aplicación (UUID): nunca derivan del nombre que envía el cliente.
 */
public interface StorageService {

    /** Guarda el contenido completo de {@code content} bajo {@code key}. Si la clave ya existe → {@link IOException}. */
    void store(String key, InputStream content) throws IOException;

    /** Abre el contenido para lectura; quien llama cierra el stream. Clave inexistente → {@link java.io.FileNotFoundException}. */
    InputStream open(String key) throws IOException;

    /** Borra el contenido; idempotente (no falla si no existe). */
    void delete(String key) throws IOException;
}
