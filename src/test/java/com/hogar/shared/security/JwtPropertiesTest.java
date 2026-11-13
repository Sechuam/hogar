package com.hogar.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** El arranque debe fallar con un secreto JWT débil o mal codificado, nunca firmar con él. */
class JwtPropertiesTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    @Test
    void acceptsBase64SecretOfAtLeast32Bytes() {
        String secret = Base64.getEncoder().encodeToString(new byte[32]);

        JwtProperties props = new JwtProperties(" " + secret + " ", "hogar-api", TTL, Duration.ofDays(30));

        assertThat(props.secretBytes()).hasSize(32);
    }

    @Test
    void rejectsSecretShorterThan32Bytes() {
        String secret = Base64.getEncoder().encodeToString(new byte[31]);

        assertThatThrownBy(() -> new JwtProperties(secret, "hogar-api", TTL, TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    @Test
    void rejectsSecretThatIsNotBase64() {
        assertThatThrownBy(() -> new JwtProperties("esto no es base64!!", "hogar-api", TTL, TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    void blankSecretIsLeftToBeanValidation() {
        // @NotBlank lo rechaza al enlazar las propiedades; el constructor no debe lanzar otra cosa antes.
        assertThat(new JwtProperties("", "hogar-api", TTL, TTL).secret()).isEmpty();
    }
}
