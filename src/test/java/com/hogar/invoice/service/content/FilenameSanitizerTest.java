package com.hogar.invoice.service.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FilenameSanitizerTest {

    @Test
    void keepsAnOrdinaryName() {
        assertThat(FilenameSanitizer.sanitize("ticket-mercadona 2026_11.pdf")).isEqualTo("ticket-mercadona 2026_11.pdf");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "../../etc/passwd|passwd",
            "/var/lib/hogar/storage/x.pdf|x.pdf",
            "..\\..\\Windows\\System32\\evil.pdf|evil.pdf",
            "C:\\Users\\ana\\Desktop\\factura luz.pdf|factura luz.pdf",
            "carpeta/sub\\mixto.png|mixto.png",
            "./foto.jpg|foto.jpg"
    })
    void dropsAnyPath(String original, String expected) {
        assertThat(FilenameSanitizer.sanitize(original)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "..", ".", "../", "a/..", "\\", "\"\"", "\u0000\u0001\u001f", "\u202e", " \t\n "})
    void fallsBackToDefaultWhenNothingUsableRemains(String original) {
        assertThat(FilenameSanitizer.sanitize(original)).isEqualTo(FilenameSanitizer.DEFAULT_NAME);
    }

    @Test
    void removesQuotesAndControlCharacters() {
        assertThat(FilenameSanitizer.sanitize("fac\"tura\".pdf")).isEqualTo("factura.pdf");
        assertThat(FilenameSanitizer.sanitize("a\u0000b\u0007c\u001b[31m.pdf")).isEqualTo("abc[31m.pdf");
        assertThat(FilenameSanitizer.sanitize("linea1\r\nX-Injected: 1.pdf")).isEqualTo("linea1X-Injected: 1.pdf");
        assertThat(FilenameSanitizer.sanitize("del\u007f\u0085.pdf")).isEqualTo("del.pdf");
        assertThat(FilenameSanitizer.sanitize("tab\there.pdf")).isEqualTo("tabhere.pdf");
    }

    @Test
    void removesInvisibleFormatAndSeparatorCharacters() {
        // U+202E (RIGHT-TO-LEFT OVERRIDE) haría que "factura\u202Efdp.exe" se viera como "facturaexe.pdf".
        assertThat(FilenameSanitizer.sanitize("factura\u202Efdp.exe")).isEqualTo("facturafdp.exe");
        assertThat(FilenameSanitizer.sanitize("a\u200Bb\uFEFFc\u2028d\u2029.pdf")).isEqualTo("abcd.pdf");
        assertThat(FilenameSanitizer.sanitize("priv\uE000.pdf")).isEqualTo("priv.pdf");
        assertThat(FilenameSanitizer.sanitize("lone\uD800surrogate.pdf")).isEqualTo("lonesurrogate.pdf");
    }

    @Test
    void keepsUnicodeAndNormalizesToNfc() {
        assertThat(FilenameSanitizer.sanitize("recibo_año_€_日本語_😀.pdf")).isEqualTo("recibo_año_€_日本語_😀.pdf");
        // "n" + tilde combinante (NFD) → "ñ" (NFC)
        assertThat(FilenameSanitizer.sanitize("an\u0303o.pdf")).isEqualTo("a\u00f1o.pdf");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(FilenameSanitizer.sanitize("  factura.pdf  ")).isEqualTo("factura.pdf");
        assertThat(FilenameSanitizer.sanitize("dir/  factura.pdf")).isEqualTo("factura.pdf");
    }

    @Test
    void truncatesVeryLongNamesTo255CodePoints() {
        String result = FilenameSanitizer.sanitize("a".repeat(10_000) + ".pdf");
        assertThat(result).hasSize(255).isEqualTo("a".repeat(255));
    }

    @Test
    void nameOfExactly255IsKept() {
        String name = "b".repeat(251) + ".pdf";
        assertThat(FilenameSanitizer.sanitize(name)).isEqualTo(name);
    }

    @Test
    void truncationNeverSplitsSurrogatePairs() {
        String emojis = "😀".repeat(300);
        String result = FilenameSanitizer.sanitize(emojis);
        assertThat(result.codePointCount(0, result.length())).isEqualTo(255);
        assertThat(result).isEqualTo("😀".repeat(255));
        assertThat(result.chars().filter(c -> Character.isSurrogate((char) c)).count()).isEqualTo(510);
    }

    @Test
    void lengthIsCountedAfterRemovingDisallowedCharacters() {
        String name = "\u0000".repeat(500) + "c".repeat(200);
        assertThat(FilenameSanitizer.sanitize(name)).isEqualTo("c".repeat(200));
    }

    @Test
    void whitespaceLeftAtTheCutIsTrimmed() {
        String name = "d".repeat(254) + "     tail";
        assertThat(FilenameSanitizer.sanitize(name)).isEqualTo("d".repeat(254));
    }
}
