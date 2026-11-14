package com.hogar.invoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class InvoiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID EXPENSE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final String KEY = UUID.randomUUID().toString();
    private static final String SHA = "a".repeat(64);

    @Test
    void createsWithAllMetadataAsNew() {
        Invoice invoice = Invoice.create(HOUSEHOLD, EXPENSE, KEY, "application/pdf", 1234, SHA, "luz marzo.pdf",
                USER, NOW);

        assertThat(invoice.getId()).isNotNull();
        assertThat(invoice.isNew()).isTrue();
        assertThat(invoice.getHouseholdId()).isEqualTo(HOUSEHOLD);
        assertThat(invoice.getExpenseId()).isEqualTo(EXPENSE);
        assertThat(invoice.getStorageKey()).isEqualTo(KEY);
        assertThat(invoice.getContentType()).isEqualTo("application/pdf");
        assertThat(invoice.getSizeBytes()).isEqualTo(1234);
        assertThat(invoice.getSha256()).isEqualTo(SHA);
        assertThat(invoice.getFilename()).isEqualTo("luz marzo.pdf");
        assertThat(invoice.getUploadedBy()).isEqualTo(USER);
        assertThat(invoice.getCreatedAt()).isEqualTo(NOW);
        assertThat(invoice.toString()).contains(invoice.getId().toString()).doesNotContain(SHA);
    }

    @Test
    void isNoLongerNewOncePersistedOrLoaded() {
        Invoice invoice = valid();

        invoice.markNotNew();

        assertThat(invoice.isNew()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/pdf", "image/jpeg", "image/png", "image/webp"})
    void acceptsEverySupportedContentType(String contentType) {
        assertThat(Invoice.create(HOUSEHOLD, EXPENSE, KEY, contentType, 1, SHA, "f", USER, NOW).getContentType())
                .isEqualTo(contentType);
        assertThat(Invoice.SUPPORTED_CONTENT_TYPES).contains(contentType);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"text/plain", "APPLICATION/PDF", "image/gif", "application/pdf "})
    void rejectsUnsupportedContentType(String contentType) {
        assertThatThrownBy(() -> Invoice.create(HOUSEHOLD, EXPENSE, KEY, contentType, 1, SHA, "f", USER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonPositiveSize(long size) {
        assertThatThrownBy(() -> Invoice.create(HOUSEHOLD, EXPENSE, KEY, "image/png", size, SHA, "f", USER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "gaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    })
    void rejectsMalformedSha256(String sha256) {
        assertThatThrownBy(() -> Invoice.create(HOUSEHOLD, EXPENSE, KEY, "image/png", 1, sha256, "f", USER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "a/b.pdf", "a\\b.pdf", "a\"b.pdf", "a\nb.pdf", "a\u0000b.pdf", "a\u007Fb.pdf"})
    void rejectsBlankOrUnsafeFilename(String filename) {
        assertThatThrownBy(() -> Invoice.create(HOUSEHOLD, EXPENSE, KEY, "image/png", 1, SHA, filename, USER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filenameLengthIsLimited() {
        String max = "a".repeat(Invoice.MAX_FILENAME_LENGTH);

        assertThat(Invoice.create(HOUSEHOLD, EXPENSE, KEY, "image/png", 1, SHA, max, USER, NOW).getFilename())
                .isEqualTo(max);
        assertThatThrownBy(() -> Invoice.create(HOUSEHOLD, EXPENSE, KEY, "image/png", 1, SHA, max + "a", USER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * El límite es de 255 <b>caracteres</b> como en PostgreSQL ({@code varchar(255)} cuenta puntos de
     * código) y como recorta {@code FilenameSanitizer}; no de 255 unidades UTF-16. Un nombre con emojis
     * (2 unidades UTF-16 cada uno) ya saneado no puede hacer fallar la subida con un 500.
     */
    @Test
    void filenameLengthIsCountedInCodePointsLikePostgres() {
        String max = "😀".repeat(Invoice.MAX_FILENAME_LENGTH);

        assertThat(Invoice.create(HOUSEHOLD, EXPENSE, KEY, "image/png", 1, SHA, max, USER, NOW).getFilename())
                .isEqualTo(max);
        assertThatThrownBy(() -> Invoice.create(HOUSEHOLD, EXPENSE, KEY, "image/png", 1, SHA, max + "😀", USER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsUnicodeFilename() {
        assertThat(Invoice.create(HOUSEHOLD, EXPENSE, KEY, "image/png", 1, SHA, "factura ñandú €.png", USER, NOW)
                .getFilename()).isEqualTo("factura ñandú €.png");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void rejectsBlankStorageKey(String key) {
        assertThatThrownBy(() -> Invoice.create(HOUSEHOLD, EXPENSE, key, "image/png", 1, SHA, "f", USER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storageKeyLengthIsLimited() {
        String max = "k".repeat(Invoice.MAX_STORAGE_KEY_LENGTH);

        assertThat(Invoice.create(HOUSEHOLD, EXPENSE, max, "image/png", 1, SHA, "f", USER, NOW).getStorageKey())
                .isEqualTo(max);
        assertThatThrownBy(() -> Invoice.create(HOUSEHOLD, EXPENSE, max + "k", "image/png", 1, SHA, "f", USER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresReferencesAndTimestamp() {
        assertThatNullPointerException()
                .isThrownBy(() -> Invoice.create(null, EXPENSE, KEY, "image/png", 1, SHA, "f", USER, NOW));
        assertThatNullPointerException()
                .isThrownBy(() -> Invoice.create(HOUSEHOLD, null, KEY, "image/png", 1, SHA, "f", USER, NOW));
        assertThatNullPointerException()
                .isThrownBy(() -> Invoice.create(HOUSEHOLD, EXPENSE, KEY, "image/png", 1, SHA, "f", null, NOW));
        assertThatNullPointerException()
                .isThrownBy(() -> Invoice.create(HOUSEHOLD, EXPENSE, KEY, "image/png", 1, SHA, "f", USER, null));
    }

    @Test
    void equalityIsById() {
        Invoice invoice = valid();
        Invoice other = valid();

        assertThat(invoice).isEqualTo(invoice).isNotEqualTo(other).isNotEqualTo("x");
        assertThat(invoice.hashCode()).isEqualTo(invoice.getId().hashCode());
    }

    private static Invoice valid() {
        return Invoice.create(HOUSEHOLD, EXPENSE, UUID.randomUUID().toString(), "image/png", 1, SHA, "f", USER, NOW);
    }
}
