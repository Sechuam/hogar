package com.hogar.invoice.config;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Configuración de facturas ({@code hogar.invoices.*}).
 *
 * @param maxSize tamaño máximo de una factura (por defecto 10 MB). Es el límite de la aplicación,
 *                comprobado en streaming; el de multipart de Spring es solo una primera barrera.
 */
@ConfigurationProperties("hogar.invoices")
public record InvoiceProperties(@DefaultValue("10MB") DataSize maxSize) {

    public InvoiceProperties {
        Objects.requireNonNull(maxSize, "hogar.invoices.max-size");
        if (maxSize.toBytes() <= 0) {
            throw new IllegalArgumentException("hogar.invoices.max-size must be positive");
        }
    }
}
