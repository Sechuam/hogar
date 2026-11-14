package com.hogar.invoice.storage;

import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del almacenamiento en disco local ({@code hogar.storage.local.*}).
 *
 * @param baseDir directorio raíz de los ficheros; se crea al arrancar si no existe
 */
@ConfigurationProperties("hogar.storage.local")
public record LocalStorageProperties(Path baseDir) {

    public LocalStorageProperties {
        Objects.requireNonNull(baseDir, "hogar.storage.local.base-dir");
    }
}
