package com.hogar.invoice.service.content;

import java.text.Normalizer;

/**
 * Sanea el nombre original de una factura. El resultado es solo un metadato informativo (en disco
 * el fichero se llama por un UUID) que luego viaja en {@code Content-Disposition} y en JSON:
 *
 * <ul>
 *   <li>se queda solo el último segmento tras {@code /} o {@code \} (sin ruta);</li>
 *   <li>se eliminan caracteres de control, de formato invisibles (p. ej. U+202E, que invierte el
 *       texto y permite disfrazar {@code fdp.exe} como {@code exe.pdf}), separadores de línea y
 *       {@code "};</li>
 *   <li>se normaliza a NFC, se recortan espacios y se limita a 255 caracteres (puntos de código, como
 *       cuenta PostgreSQL), sin partir pares sustitutos;</li>
 *   <li>si queda vacío, {@code .} o {@code ..} → {@value #DEFAULT_NAME}.</li>
 * </ul>
 */
public final class FilenameSanitizer {

    public static final String DEFAULT_NAME = "factura";
    public static final int MAX_LENGTH = 255;

    private FilenameSanitizer() {
    }

    public static String sanitize(String original) {
        if (original == null) {
            return DEFAULT_NAME;
        }
        String name = Normalizer.normalize(original, Normalizer.Form.NFC);
        int lastSeparator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(lastSeparator + 1);

        StringBuilder clean = new StringBuilder(name.length());
        name.codePoints().filter(FilenameSanitizer::isAllowed).forEach(clean::appendCodePoint);
        name = clean.toString().strip();

        if (name.codePointCount(0, name.length()) > MAX_LENGTH) {
            name = name.substring(0, name.offsetByCodePoints(0, MAX_LENGTH)).strip();
        }
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return DEFAULT_NAME;
        }
        return name;
    }

    private static boolean isAllowed(int codePoint) {
        if (codePoint == '"') {
            return false;
        }
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> false;
            default -> true;
        };
    }
}
