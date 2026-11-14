package com.hogar.invoice.config;

import com.hogar.invoice.storage.LocalFileSystemStorage;
import com.hogar.invoice.storage.LocalStorageProperties;
import com.hogar.invoice.storage.StorageService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cableado de facturas. Los servicios dependen solo de {@link StorageService}: una implementación
 * S3 se añadiría aquí (p. ej. elegida por propiedad) sin tocarlos.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({InvoiceProperties.class, LocalStorageProperties.class})
class InvoiceConfig {

    @Bean
    StorageService storageService(LocalStorageProperties properties) {
        return new LocalFileSystemStorage(properties.baseDir());
    }
}
