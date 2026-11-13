package com.hogar.household.service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Random;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Códigos de invitación de {@value #LENGTH} caracteres en Crockford Base32 (sin I, L, O, U:
 * no se confunden con 1/0 ni forman palabras malsonantes al dictarlos). 10 símbolos de 5 bits
 * = 50 bits de {@link SecureRandom}: inviable de adivinar con el volumen de intentos de una API.
 */
@Component
public class InvitationCodeGenerator {

    static final int LENGTH = 10;
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private final Random random;

    @Autowired
    public InvitationCodeGenerator() {
        this(new SecureRandom());
    }

    InvitationCodeGenerator(Random random) {
        this.random = random;
    }

    public String generate() {
        char[] code = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            code[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(code);
    }

    /**
     * Normaliza lo que teclea el usuario: sin espacios ni guiones, en mayúsculas y con las
     * equivalencias de Crockford (O → 0, I/L → 1) para tolerar errores al copiar el código.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.strip()
                .replace("-", "")
                .toUpperCase(Locale.ROOT)
                .replace('O', '0')
                .replace('I', '1')
                .replace('L', '1');
    }
}
