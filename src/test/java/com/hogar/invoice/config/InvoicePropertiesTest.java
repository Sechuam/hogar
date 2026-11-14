package com.hogar.invoice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.invoice.storage.LocalStorageProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

class InvoicePropertiesTest {

    @Test
    void defaultsToTenMegabytes() {
        InvoiceProperties properties = bind(Map.of());

        assertThat(properties.maxSize()).isEqualTo(DataSize.ofMegabytes(10));
    }

    @Test
    void bindsConfiguredSize() {
        assertThat(bind(Map.of("hogar.invoices.max-size", "512KB")).maxSize()).isEqualTo(DataSize.ofKilobytes(512));
    }

    @Test
    void rejectsZeroNegativeOrMissingSize() {
        assertThatThrownBy(() -> new InvoiceProperties(DataSize.ofBytes(0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvoiceProperties(DataSize.ofBytes(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvoiceProperties(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void localStorageRequiresBaseDir() {
        assertThatThrownBy(() -> new LocalStorageProperties(null)).isInstanceOf(NullPointerException.class);
    }

    private static InvoiceProperties bind(Map<String, String> values) {
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        return binder.bindOrCreate("hogar.invoices", InvoiceProperties.class);
    }
}
